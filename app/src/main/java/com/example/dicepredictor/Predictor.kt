package com.example.dicepredictor

import com.example.dicepredictor.db.DiceResult
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

object Predictor {

    const val WINDOW = 10_000
    const val T_THRESHOLD = 11
    const val STREAK_THRESHOLD = 5

    private const val HALF_LIFE_HOURS = 10.0
    private const val SESSION_GAP_MINUTES = 200
    private const val SESSION_GAP_MS = SESSION_GAP_MINUTES * 60L * 1000L

    private const val E_EWMA_S = 0
    private const val E_EWMA_M = 1
    private const val E_EWMA_L = 2
    private const val E_MKV1 = 3
    private const val E_MKV2 = 4
    private const val E_MKV3 = 5
    private const val E_PAT4 = 6
    private const val E_ANTI_S = 7
    private const val E_CROWD = 8
    private const val E_WIN20 = 9
    private const val E_WIN50 = 10
    private const val E_STREAK = 11
    private const val E_ANTI_ENS = 12
    private const val E_ALT = 13
    private const val E_BLOCK2 = 14
    private const val E_STREAK5 = 15
    private const val E_REGIME30 = 16
    private const val N_EXPERTS = 17

    private val EXPERT_NAMES = arrayOf(
        "EWMA-S", "EWMA-M", "EWMA-L",
        "Markov1", "Markov2", "Markov3",
        "Pattern4", "AntiS", "Crowd",
        "Win20", "Win50", "StreakBrk", "AntiEns",
        "Alt", "Block2", "Streak5", "Regime30"
    )

    private const val ADV_CORR_THRESHOLD = -0.15
    private const val ADV_N_MIN = 100
    private const val REGIME_STRONG = 0.20
    private const val REGIME_DROP = 0.10

    private const val BIAS_DECAY = 0.95
    private const val ADV_DECAY = 0.95
    private const val ACC_DECAY = 0.90
    private const val EXPERT_ACC_DECAY = 0.95

    data class Prediction(
        val sampleCount: Int,
        val lastSum: Int,
        val nextIsT: Boolean,
        val probT: Double,
        val probX: Double,
        val bestSum: Int,
        val bestSumProb: Double,
        val topSums: List<Pair<Int, Double>>,
        val dieProbs: List<Map<Int, Double>>,
        val ensembleAcc: Double,
        val recentAcc: Double,
        val bestExpertName: String,
        val bestExpertAcc: Double,
        val worstExpertName: String,
        val regimeChange: Boolean,
        val regimeShiftScore: Double,
        val adversarialMode: Boolean,
        val adversarialCorr: Double,
        val streakClass: Int,
        val streakSum: Int,
        val biasT: Double,
        val crowdRate: Double,
        val contrarianAdj: Double,
        val expertWeights: List<Pair<String, Double>>,
        val flipped: Boolean,
        val rawPT: Double,
        val win20T: Double,
        val win50T: Double,
        val streak5Signal: Double,
        val regime30T: Double,
        val regime30Signal: Double,
        val dataAgeHours: Double,
        val sessionGapHours: Double,
        val currentSessionSize: Int,
        val numSessions: Int,
        val lastShrinkFactor: Double
    )

    fun classify(sum: Int): Char = if (sum >= T_THRESHOLD) 'T' else 'X'

    fun predict(all: List<DiceResult>): Prediction? {
        if (all.isEmpty()) return null

        val h = if (all.size > WINDOW) all.subList(all.size - WINDOW, all.size) else all
        val n = h.size

        val eta = sqrt(8.0 * ln(N_EXPERTS.toDouble()) / n.toDouble())
            .coerceIn(0.02, 0.30)

        var ewmaS = 0.5; var ewmaM = 0.5; var ewmaL = 0.5
        val decS = 0.85; val decM = 0.97; val decL = 0.997

        var last1 = 0
        var m1_TT = 0.5; var m1_TX = 0.5; var m1_XT = 0.5; var m1_XX = 0.5

        var last2 = 0
        val m2_T = DoubleArray(4) { 0.5 }
        val m2_X = DoubleArray(4) { 0.5 }

        var last3 = 0
        val m3_T = DoubleArray(8) { 0.5 }
        val m3_X = DoubleArray(8) { 0.5 }

        var last4 = 0
        var last5 = 0
        val p4_T = DoubleArray(16) { 0.5 }
        val p4_X = DoubleArray(16) { 0.5 }

        var cT_T = 0.5; var cT_X = 0.5
        var cX_T = 0.5; var cX_X = 0.5

        val face1 = DoubleArray(7) { 0.5 }
        val face2 = DoubleArray(7) { 0.5 }
        val face3 = DoubleArray(7) { 0.5 }

        val win20 = BooleanArray(20)
        val win50 = BooleanArray(50)
        val win30 = BooleanArray(30)
        var idx20 = 0; var idx50 = 0; var idx30 = 0
        var count20 = 0; var count50 = 0; var count30 = 0
        var sumT20 = 0; var sumT50 = 0; var sumT30 = 0

        var curStreakT = 0

        val cumLoss = DoubleArray(N_EXPERTS)
        val expertAccEWMA = DoubleArray(N_EXPERTS) { 0.5 }

        var cumCorrect = 0.0
        var cumTotal = 0
        var recentAcc = 0.5
        var biasEWMA = 0.0
        var advEWMA = 0.0

        val predP = DoubleArray(N_EXPERTS)
        val ensHistory = DoubleArray(n)

        var numSessions = 1
        var lastShrinkFactor = 1.0

        for (i in 0 until n) {
            val r = h[i]
            val actualT = r.sum >= T_THRESHOLD

            if (i > 0) {
                val gapMs = r.timestamp - h[i - 1].timestamp
                if (gapMs > SESSION_GAP_MS) {
                    numSessions++
                    val gapHours = gapMs / 3_600_000.0
                    val shrink = exp(-gapHours / HALF_LIFE_HOURS)
                    lastShrinkFactor = shrink

                    ewmaS = 0.5 + (ewmaS - 0.5) * shrink
                    ewmaM = 0.5 + (ewmaM - 0.5) * shrink
                    ewmaL = 0.5 + (ewmaL - 0.5) * shrink

                    m1_TT = 0.5 + (m1_TT - 0.5) * shrink
                    m1_TX = 0.5 + (m1_TX - 0.5) * shrink
                    m1_XT = 0.5 + (m1_XT - 0.5) * shrink
                    m1_XX = 0.5 + (m1_XX - 0.5) * shrink

                    for (k in 0 until 4) {
                        m2_T[k] = 0.5 + (m2_T[k] - 0.5) * shrink
                        m2_X[k] = 0.5 + (m2_X[k] - 0.5) * shrink
                    }
                    for (k in 0 until 8) {
                        m3_T[k] = 0.5 + (m3_T[k] - 0.5) * shrink
                        m3_X[k] = 0.5 + (m3_X[k] - 0.5) * shrink
                    }
                    for (k in 0 until 16) {
                        p4_T[k] = 0.5 + (p4_T[k] - 0.5) * shrink
                        p4_X[k] = 0.5 + (p4_X[k] - 0.5) * shrink
                    }

                    cT_T = 0.5 + (cT_T - 0.5) * shrink
                    cT_X = 0.5 + (cT_X - 0.5) * shrink
                    cX_T = 0.5 + (cX_T - 0.5) * shrink
                    cX_X = 0.5 + (cX_X - 0.5) * shrink

                    for (e in 0 until N_EXPERTS) {
                        cumLoss[e] *= shrink
                        expertAccEWMA[e] = 0.5 + (expertAccEWMA[e] - 0.5) * shrink
                    }

                    recentAcc = 0.5 + (recentAcc - 0.5) * shrink
                    biasEWMA *= shrink
                    advEWMA *= shrink

                    curStreakT = 0

                    for (k in 0 until 20) win20[k] = false
                    for (k in 0 until 50) win50[k] = false
                    for (k in 0 until 30) win30[k] = false
                    idx20 = 0; idx50 = 0; idx30 = 0
                    count20 = 0; count50 = 0; count30 = 0
                    sumT20 = 0; sumT50 = 0; sumT30 = 0
                }
            }

            predP[E_EWMA_S] = ewmaS
            predP[E_EWMA_M] = ewmaM
            predP[E_EWMA_L] = ewmaL

            predP[E_MKV1] = if (last1 == 1) m1_TT / (m1_TT + m1_TX)
                            else m1_XT / (m1_XT + m1_XX)
            predP[E_MKV2] = m2_T[last2] / (m2_T[last2] + m2_X[last2])
            predP[E_MKV3] = m3_T[last3] / (m3_T[last3] + m3_X[last3])
            predP[E_PAT4] = p4_T[last4] / (p4_T[last4] + p4_X[last4])
            predP[E_ANTI_S] = 1.0 - ewmaS

            predP[E_CROWD] = when (r.crowdChoice) {
                'T' -> cT_T / (cT_T + cT_X)
                'X' -> cX_T / (cX_T + cX_X)
                else -> 0.5
            }

            predP[E_WIN20] = if (count20 > 0) sumT20.toDouble() / count20 else 0.5
            predP[E_WIN50] = if (count50 > 0) sumT50.toDouble() / count50 else 0.5

            predP[E_STREAK] = when {
                curStreakT >= 3 -> 0.30
                curStreakT <= -3 -> 0.70
                else -> 0.5
            }
            predP[E_ANTI_ENS] = if (i > 0) 1.0 - ensHistory[i - 1] else 0.5

            predP[E_ALT] = if (i >= 3) {
                when {
                    last3 == 0 && last2 == 1 && last1 == 0 -> 0.70
                    last3 == 1 && last2 == 0 && last1 == 1 -> 0.30
                    last4 == 0 && last3 == 1 && last2 == 0 && last1 == 1 -> 0.72
                    last4 == 1 && last3 == 0 && last2 == 1 && last1 == 0 -> 0.28
                    else -> 0.5
                }
            } else 0.5

            predP[E_BLOCK2] = if (i >= 4) {
                when {
                    last4 == 0 && last3 == 0 && last2 == 1 && last1 == 1 -> 0.32
                    last4 == 1 && last3 == 1 && last2 == 0 && last1 == 0 -> 0.68
                    last5 == 0 && last4 == 0 && last3 == 1 &&
                        last2 == 1 && last1 == 1 -> 0.68
                    last5 == 1 && last4 == 1 && last3 == 0 &&
                        last2 == 0 && last1 == 0 -> 0.32
                    else -> 0.5
                }
            } else 0.5

            predP[E_STREAK5] = when {
                curStreakT >= 8 -> 0.12
                curStreakT >= 7 -> 0.18
                curStreakT >= 6 -> 0.22
                curStreakT >= 5 -> 0.28
                curStreakT <= -8 -> 0.88
                curStreakT <= -7 -> 0.82
                curStreakT <= -6 -> 0.78
                curStreakT <= -5 -> 0.72
                else -> 0.5
            }

            val p30 = if (count30 > 0) sumT30.toDouble() / count30 else 0.5
            predP[E_REGIME30] = when {
                p30 >= 0.65 -> 0.25
                p30 >= 0.55 -> 0.38
                p30 <= 0.35 -> 0.75
                p30 <= 0.45 -> 0.62
                else -> 0.5
            }

            for (e in 0 until N_EXPERTS) {
                val predictedT = predP[e] >= 0.5
                val isCorrect = predictedT == actualT

                if (isCorrect) {
                    expertAccEWMA[e] = EXPERT_ACC_DECAY * expertAccEWMA[e] +
                        (1 - EXPERT_ACC_DECAY) * 1.0
                } else {
                    cumLoss[e] += 1.0
                    expertAccEWMA[e] = EXPERT_ACC_DECAY * expertAccEWMA[e]
                }
            }

            var wSumLocal = 0.0
            var ensPLocal = 0.0
            for (e in 0 until N_EXPERTS) {
                val w = exp(-eta * cumLoss[e])
                wSumLocal += w
                ensPLocal += w * predP[e]
            }
            ensPLocal /= wSumLocal
            ensHistory[i] = ensPLocal
            val ensT = ensPLocal >= 0.5

            if (ensT == actualT) {
                cumCorrect += 1.0
                recentAcc = ACC_DECAY * recentAcc + (1 - ACC_DECAY)
            } else {
                recentAcc = ACC_DECAY * recentAcc
            }
            cumTotal++

            val y = if (actualT) 1.0 else 0.0
            biasEWMA = BIAS_DECAY * biasEWMA + (1 - BIAS_DECAY) * (y - ensPLocal)

            val ensSign = if (ensT) 1.0 else -1.0
            val actualSign = if (actualT) 1.0 else -1.0
            advEWMA = ADV_DECAY * advEWMA + (1 - ADV_DECAY) * (ensSign * actualSign)

            ewmaS = decS * ewmaS + (1 - decS) * y
            ewmaM = decM * ewmaM + (1 - decM) * y
            ewmaL = decL * ewmaL + (1 - decL) * y

            if (last1 == 1) {
                if (actualT) m1_TT += 1 else m1_TX += 1
            } else {
                if (actualT) m1_XT += 1 else m1_XX += 1
            }

            if (actualT) {
                m2_T[last2] += 1.0
                m3_T[last3] += 1.0
                p4_T[last4] += 1.0
            } else {
                m2_X[last2] += 1.0
                m3_X[last3] += 1.0
                p4_X[last4] += 1.0
            }

            when (r.crowdChoice) {
                'T' -> if (actualT) cT_T += 1.0 else cT_X += 1.0
                'X' -> if (actualT) cX_T += 1.0 else cX_X += 1.0
            }

            face1[r.d1] += 1.0
            face2[r.d2] += 1.0
            face3[r.d3] += 1.0

            val bit = if (actualT) 1 else 0

            if (count20 >= 20) {
                if (win20[idx20]) sumT20--
            } else count20++
            win20[idx20] = actualT
            if (actualT) sumT20++
            idx20 = (idx20 + 1) % 20

            if (count50 >= 50) {
                if (win50[idx50]) sumT50--
            } else count50++
            win50[idx50] = actualT
            if (actualT) sumT50++
            idx50 = (idx50 + 1) % 50

            if (count30 >= 30) {
                if (win30[idx30]) sumT30--
            } else count30++
            win30[idx30] = actualT
            if (actualT) sumT30++
            idx30 = (idx30 + 1) % 30

            if (actualT) {
                curStreakT = if (curStreakT > 0) curStreakT + 1 else 1
            } else {
                curStreakT = if (curStreakT < 0) curStreakT - 1 else -1
            }

            last1 = bit
            last2 = ((last2 shl 1) or bit) and 0b11
            last3 = ((last3 shl 1) or bit) and 0b111
            last4 = ((last4 shl 1) or bit) and 0b1111
            last5 = ((last5 shl 1) or bit) and 0b11111
        }

        val finalP = DoubleArray(N_EXPERTS)
        finalP[E_EWMA_S] = ewmaS
        finalP[E_EWMA_M] = ewmaM
        finalP[E_EWMA_L] = ewmaL
        finalP[E_MKV1] = if (last1 == 1) m1_TT / (m1_TT + m1_TX)
                          else m1_XT / (m1_XT + m1_XX)
        finalP[E_MKV2] = m2_T[last2] / (m2_T[last2] + m2_X[last2])
        finalP[E_MKV3] = m3_T[last3] / (m3_T[last3] + m3_X[last3])
        finalP[E_PAT4] = p4_T[last4] / (p4_T[last4] + p4_X[last4])
        finalP[E_ANTI_S] = 1.0 - ewmaS

        val lastCrowd = h[n - 1].crowdChoice
        finalP[E_CROWD] = when (lastCrowd) {
            'T' -> cT_T / (cT_T + cT_X)
            'X' -> cX_T / (cX_T + cX_X)
            else -> 0.5
        }

        finalP[E_WIN20] = if (count20 > 0) sumT20.toDouble() / count20 else 0.5
        finalP[E_WIN50] = if (count50 > 0) sumT50.toDouble() / count50 else 0.5

        finalP[E_STREAK] = when {
            curStreakT >= 3 -> 0.30
            curStreakT <= -3 -> 0.70
            else -> 0.5
        }
        finalP[E_ANTI_ENS] = if (n > 0) 1.0 - ensHistory[n - 1] else 0.5

        finalP[E_ALT] = if (n >= 3) {
            when {
                last3 == 0 && last2 == 1 && last1 == 0 -> 0.70
                last3 == 1 && last2 == 0 && last1 == 1 -> 0.30
                last4 == 0 && last3 == 1 && last2 == 0 && last1 == 1 -> 0.72
                last4 == 1 && last3 == 0 && last2 == 1 && last1 == 0 -> 0.28
                else -> 0.5
            }
        } else 0.5

        finalP[E_BLOCK2] = if (n >= 5) {
            when {
                last4 == 0 && last3 == 0 && last2 == 1 && last1 == 1 -> 0.32
                last4 == 1 && last3 == 1 && last2 == 0 && last1 == 0 -> 0.68
                last5 == 0 && last4 == 0 && last3 == 1 &&
                    last2 == 1 && last1 == 1 -> 0.68
                last5 == 1 && last4 == 1 && last3 == 0 &&
                    last2 == 0 && last1 == 0 -> 0.32
                else -> 0.5
            }
        } else 0.5

        finalP[E_STREAK5] = when {
            curStreakT >= 8 -> 0.12
            curStreakT >= 7 -> 0.18
            curStreakT >= 6 -> 0.22
            curStreakT >= 5 -> 0.28
            curStreakT <= -8 -> 0.88
            curStreakT <= -7 -> 0.82
            curStreakT <= -6 -> 0.78
            curStreakT <= -5 -> 0.72
            else -> 0.5
        }

        val finalP30 = if (count30 > 0) sumT30.toDouble() / count30 else 0.5
        finalP[E_REGIME30] = when {
            finalP30 >= 0.65 -> 0.25
            finalP30 >= 0.55 -> 0.38
            finalP30 <= 0.35 -> 0.75
            finalP30 <= 0.45 -> 0.62
            else -> 0.5
        }

        val weights = DoubleArray(N_EXPERTS)
        var wSum2 = 0.0
        for (e in 0 until N_EXPERTS) {
            weights[e] = exp(-eta * cumLoss[e])
            wSum2 += weights[e]
        }
        for (e in 0 until N_EXPERTS) weights[e] /= wSum2

        var pT = 0.0
        for (e in 0 until N_EXPERTS) pT += weights[e] * finalP[e]
        val rawPT = pT

        val biasT = biasEWMA.coerceIn(-0.10, 0.10)
        pT = (pT + biasT * 0.5).coerceIn(0.02, 0.98)

        val advCorr = advEWMA.coerceIn(-1.0, 1.0)
        var adversarialMode = false
        var flipped = false
        if (cumTotal >= ADV_N_MIN && advCorr < ADV_CORR_THRESHOLD) {
            adversarialMode = true
            flipped = true
            val alpha = (-advCorr).coerceAtMost(0.55)
            pT = pT * (1 - alpha) + (1 - pT) * alpha
        }

        val longAcc = if (cumTotal > 0) cumCorrect / cumTotal else 0.5

        val recentStart = maxOf(0, n - 20)
        val midEnd = recentStart
        val midStart = maxOf(0, recentStart - 20)

        var recentT = 0
        var recentN = 0
        for (i in recentStart until n) {
            if (h[i].sum >= T_THRESHOLD) recentT++
            recentN++
        }
        var midT = 0
        var midN = 0
        for (i in midStart until midEnd) {
            if (h[i].sum >= T_THRESHOLD) midT++
            midN++
        }
        val recentRate = if (recentN > 0) recentT.toDouble() / recentN else 0.5
        val midRate = if (midN > 0) midT.toDouble() / midN else 0.5

        val regimeShiftScore = abs(recentRate - midRate)
        val regimeChange = n > 40 && (
            regimeShiftScore > REGIME_STRONG ||
            (cumTotal > 100 && recentAcc < longAcc - REGIME_DROP)
        )

        if (regimeChange) pT = pT * 0.70 + 0.5 * 0.30

        pT = pT.coerceIn(0.02, 0.98)
        val pX = 1.0 - pT

        val pf1 = DoubleArray(7); val pf2 = DoubleArray(7); val pf3 = DoubleArray(7)
        var s1 = 0.0; var s2 = 0.0; var s3 = 0.0
        for (v in 1..6) { s1 += face1[v]; s2 += face2[v]; s3 += face3[v] }
        for (v in 1..6) {
            pf1[v] = face1[v] / s1
            pf2[v] = face2[v] / s2
            pf3[v] = face3[v] / s3
        }

        val sumProbs = DoubleArray(19)
        for (a in 1..6) {
            val pa = pf1[a]
            for (b in 1..6) {
                val pab = pa * pf2[b]
                for (c in 1..6) sumProbs[a + b + c] += pab * pf3[c]
            }
        }

        val ordered = (3..18).sortedByDescending { sumProbs[it] }
        val topSums = ordered.take(5).map { it to sumProbs[it] }
        val bestSum = ordered.first()
        val bestSumProb = sumProbs[bestSum]

        val dieProbsMaps = listOf(
            (1..6).associateWith { pf1[it] },
            (1..6).associateWith { pf2[it] },
            (1..6).associateWith { pf3[it] }
        )

        var streakClass = 0
        for (i in n - 1 downTo 0) {
            val r = h[i]
            if (!r.hasPred) break
            if (r.classCorrect) streakClass++ else break
        }
        var streakSum = 0
        for (i in n - 1 downTo 0) {
            val r = h[i]
            if (!r.hasPred) break
            if (r.sumCorrect) streakSum++ else break
        }

        var bestIdx = 0
        var worstIdx = 0
        var bestA = -1.0
        var worstA = 2.0
        for (e in 0 until N_EXPERTS) {
            val a = expertAccEWMA[e]
            if (a > bestA) { bestA = a; bestIdx = e }
            if (a < worstA) { worstA = a; worstIdx = e }
        }

        val crowdRate = cT_X / (cT_T + cT_X)
        val contrarianAdj = crowdRate - 0.5

        val now = System.currentTimeMillis()
        val latestTs = h[n - 1].timestamp
        val dataAgeHours = ((now - latestTs) / 3_600_000.0).coerceAtLeast(0.0)

        val sessionGapHours = if (n >= 2) {
            (h[n - 1].timestamp - h[n - 2].timestamp) / 3_600_000.0
        } else 0.0

        var currentSessionSize = 1
        for (i in n - 2 downTo 0) {
            val gap = h[i + 1].timestamp - h[i].timestamp
            if (gap > SESSION_GAP_MS) break
            currentSessionSize++
        }

        val streak5Signal = when {
            curStreakT >= 5 -> (curStreakT / 10.0).coerceAtMost(1.0)
            curStreakT <= -5 -> (-curStreakT / 10.0).coerceAtMost(1.0)
            else -> 0.0
        }
        val regime30Signal = abs(finalP30 - 0.5) * 2.0

        return Prediction(
            sampleCount = n,
            lastSum = h[n - 1].sum,
            nextIsT = pT >= pX,
            probT = pT, probX = pX,
            bestSum = bestSum, bestSumProb = bestSumProb,
            topSums = topSums, dieProbs = dieProbsMaps,
            ensembleAcc = longAcc,
            recentAcc = recentAcc,
            bestExpertName = EXPERT_NAMES[bestIdx],
            bestExpertAcc = bestA,
            worstExpertName = EXPERT_NAMES[worstIdx],
            regimeChange = regimeChange,
            regimeShiftScore = regimeShiftScore,
            adversarialMode = adversarialMode,
            adversarialCorr = advCorr,
            streakClass = streakClass,
            streakSum = streakSum,
            biasT = biasT,
            crowdRate = crowdRate,
            contrarianAdj = contrarianAdj,
            expertWeights = (0 until N_EXPERTS).map { EXPERT_NAMES[it] to weights[it] },
            flipped = flipped,
            rawPT = rawPT,
            win20T = if (count20 > 0) sumT20.toDouble() / count20 else 0.5,
            win50T = if (count50 > 0) sumT50.toDouble() / count50 else 0.5,
            streak5Signal = streak5Signal,
            regime30T = finalP30,
            regime30Signal = regime30Signal,
            dataAgeHours = dataAgeHours,
            sessionGapHours = sessionGapHours,
            currentSessionSize = currentSessionSize,
            numSessions = numSessions,
            lastShrinkFactor = lastShrinkFactor
        )
    }
}
