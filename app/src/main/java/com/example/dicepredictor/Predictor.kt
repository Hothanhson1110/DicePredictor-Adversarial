package com.example.dicepredictor

import com.example.dicepredictor.db.DiceResult
import kotlin.math.abs
import kotlin.math.exp

object Predictor {

    const val WINDOW = 10_000
    const val T_THRESHOLD = 11
    const val STREAK_THRESHOLD = 5

    // ============================================================
    // TIME-AWARE CONFIG
    // ============================================================
    /** Sau khoảng này, trọng số dữ liệu cũ giảm còn 50%. */
    private const val HALF_LIFE_HOURS = 10.0

    /** Nghỉ > ngưỡng này → session mới → shrink state. */
    private const val SESSION_GAP_MINUTES = 200

    /** Session gap tính bằng milliseconds. */
    private const val SESSION_GAP_MS =
        SESSION_GAP_MINUTES * 60L * 1000L

    // 13 chuyên gia
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
    private const val N_EXPERTS = 13

    private val EXPERT_NAMES = arrayOf(
        "EWMA-S", "EWMA-M", "EWMA-L",
        "Markov1", "Markov2", "Markov3",
        "Pattern4", "AntiS", "Crowd",
        "Win20", "Win50", "StreakBrk", "AntiEns"
    )

    private const val ETA = 0.05
    private const val REGIME_DROP = 0.10
    private const val ADV_N_MIN = 60
    private const val ADV_CORR_THRESHOLD = -0.05
    private const val REGIME_STRONG = 0.20

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

        // ---- TIME-AWARE FIELDS ----
        val dataAgeHours: Double,           // tuổi dữ liệu mới nhất (giờ)
        val sessionGapHours: Double,        // gap giữa 2 ván cuối
        val currentSessionSize: Int,        // số ván trong session hiện tại
        val numSessions: Int,               // tổng số session trong 10K mẫu
        val lastShrinkFactor: Double        // hệ số shrink của session cuối
    )

    fun classify(sum: Int): Char = if (sum >= T_THRESHOLD) 'T' else 'X'

    fun predict(all: List<DiceResult>): Prediction? {
        if (all.isEmpty()) return null

        val h = if (all.size > WINDOW) all.subList(all.size - WINDOW, all.size) else all
        val n = h.size

        // Sắp xếp theo timestamp để chắc chắn (dữ liệu cũ có thể không đúng thứ tự)
        // — bỏ qua nếu list đã được sort theo id (tương đương timestamp)

        val eeta = exp(-ETA)
        val expTable = DoubleArray(n + 1)
        expTable[0] = 1.0
        for (k in 1..n) expTable[k] = expTable[k - 1] * eeta

        // ============================================================
        // State
        // ============================================================
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
        val p4_T = DoubleArray(16) { 0.5 }
        val p4_X = DoubleArray(16) { 0.5 }

        var cT_T = 0.5; var cT_X = 0.5
        var cX_T = 0.5; var cX_X = 0.5

        val face1 = DoubleArray(7) { 0.5 }
        val face2 = DoubleArray(7) { 0.5 }
        val face3 = DoubleArray(7) { 0.5 }

        // Window
        val win20 = BooleanArray(20)
        val win50 = BooleanArray(50)
        var idx20 = 0; var idx50 = 0
        var count20 = 0; var count50 = 0
        var sumT20 = 0; var sumT50 = 0

        var curStreakT = 0

        // Meta
        val cumLoss = DoubleArray(N_EXPERTS)
        val expertCorrect = IntArray(N_EXPERTS)
        val expertTotal = IntArray(N_EXPERTS)

        var cumCorrect = 0.0
        var cumTotal = 0
        var recentAcc = 0.5
        val recentDecay = 0.9

        var biasSum = 0.0
        var advSum = 0.0
        var advN = 0

        val predP = DoubleArray(N_EXPERTS)
        val ensHistory = DoubleArray(n)

        // Time-aware tracking
        var numSessions = 1
        var lastShrinkFactor = 1.0
        var sessionGapHoursLast = 0.0

        // ============================================================
        // VÒNG LẶP CHÍNH
        // ============================================================
        for (i in 0 until n) {
            val r = h[i]
            val actualT = r.sum >= T_THRESHOLD

            // ----- SESSION BOUNDARY DETECTION -----
            if (i > 0) {
                val gapMs = r.timestamp - h[i - 1].timestamp
                if (gapMs > SESSION_GAP_MS) {
                    numSessions++
                    val gapHours = gapMs / 3_600_000.0
                    sessionGapHoursLast = gapHours
                    val shrink = exp(-gapHours / HALF_LIFE_HOURS)
                    lastShrinkFactor = shrink

                    // Shrink EWMA về 0.5
                    ewmaS = 0.5 + (ewmaS - 0.5) * shrink
                    ewmaM = 0.5 + (ewmaM - 0.5) * shrink
                    ewmaL = 0.5 + (ewmaL - 0.5) * shrink

                    // Shrink Markov 1
                    m1_TT = 0.5 + (m1_TT - 0.5) * shrink
                    m1_TX = 0.5 + (m1_TX - 0.5) * shrink
                    m1_XT = 0.5 + (m1_XT - 0.5) * shrink
                    m1_XX = 0.5 + (m1_XX - 0.5) * shrink

                    // Shrink Markov 2
                    for (k in 0 until 4) {
                        m2_T[k] = 0.5 + (m2_T[k] - 0.5) * shrink
                        m2_X[k] = 0.5 + (m2_X[k] - 0.5) * shrink
                    }

                    // Shrink Markov 3
                    for (k in 0 until 8) {
                        m3_T[k] = 0.5 + (m3_T[k] - 0.5) * shrink
                        m3_X[k] = 0.5 + (m3_X[k] - 0.5) * shrink
                    }

                    // Shrink Pattern 4
                    for (k in 0 until 16) {
                        p4_T[k] = 0.5 + (p4_T[k] - 0.5) * shrink
                        p4_X[k] = 0.5 + (p4_X[k] - 0.5) * shrink
                    }

                    // Shrink Crowd
                    cT_T = 0.5 + (cT_T - 0.5) * shrink
                    cT_X = 0.5 + (cT_X - 0.5) * shrink
                    cX_T = 0.5 + (cX_T - 0.5) * shrink
                    cX_X = 0.5 + (cX_X - 0.5) * shrink

                    // Shrink Hedge cumulative loss
                    for (e in 0 until N_EXPERTS) {
                        cumLoss[e] *= shrink
                    }

                    // Shrink recent accuracy
                    recentAcc = 0.5 + (recentAcc - 0.5) * shrink

                    // Reset streak (không mang streak qua session)
                    curStreakT = 0

                    // Reset windows (dữ liệu cũ không đại diện session mới)
                    for (k in 0 until 20) win20[k] = false
                    for (k in 0 until 50) win50[k] = false
                    idx20 = 0; idx50 = 0
                    count20 = 0; count50 = 0
                    sumT20 = 0; sumT50 = 0
                }
            }

            // ----- Dự đoán của từng chuyên gia -----
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

            // ----- Cập nhật Hedge -----
            for (e in 0 until N_EXPERTS) {
                val predictedT = predP[e] >= 0.5
                if (predictedT == actualT) {
                    expertCorrect[e]++
                } else {
                    cumLoss[e] += 1.0
                }
                expertTotal[e]++
            }

            // Ensemble cho ván i
            var wSumLocal = 0.0
            var ensPLocal = 0.0
            for (e in 0 until N_EXPERTS) {
                val w = expTable[cumLoss[e].toInt()]
                wSumLocal += w
                ensPLocal += w * predP[e]
            }
            ensPLocal /= wSumLocal
            ensHistory[i] = ensPLocal
            val ensT = ensPLocal >= 0.5

            if (ensT == actualT) {
                cumCorrect += 1.0
                recentAcc = recentDecay * recentAcc + (1 - recentDecay)
            } else {
                recentAcc = recentDecay * recentAcc
            }
            cumTotal++

            val y = if (actualT) 1.0 else 0.0
            biasSum += y - ensPLocal
            advSum += (if (ensT) 1.0 else -1.0) * (if (actualT) 1.0 else -1.0)
            advN++

            // ----- Update chuyên gia -----
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

            // Window
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

            // Streak
            if (actualT) {
                curStreakT = if (curStreakT > 0) curStreakT + 1 else 1
            } else {
                curStreakT = if (curStreakT < 0) curStreakT - 1 else -1
            }

            // Bit shift
            last1 = bit
            last2 = ((last2 shl 1) or bit) and 0b11
            last3 = ((last3 shl 1) or bit) and 0b111
            last4 = ((last4 shl 1) or bit) and 0b1111
        }

        // ============================================================
        // DỰ ĐOÁN CUỐI
        // ============================================================
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

        // Hedge weights
        val weights = DoubleArray(N_EXPERTS)
        var wSum2 = 0.0
        for (e in 0 until N_EXPERTS) {
            weights[e] = expTable[cumLoss[e].toInt()]
            wSum2 += weights[e]
        }
        for (e in 0 until N_EXPERTS) weights[e] /= wSum2

        var pT = 0.0
        for (e in 0 until N_EXPERTS) pT += weights[e] * finalP[e]
        val rawPT = pT

        val biasT = if (cumTotal > 0)
            (biasSum / cumTotal).coerceIn(-0.10, 0.10) else 0.0
        pT = (pT + biasT * 0.5).coerceIn(0.02, 0.98)

        // Adversarial
        val advCorr = if (advN > 0) advSum / advN else 0.0
        var adversarialMode = false
        var flipped = false
        if (advN >= ADV_N_MIN && advCorr < ADV_CORR_THRESHOLD) {
            adversarialMode = true
            flipped = true
            val alpha = (-advCorr).coerceAtMost(0.55)
            pT = pT * (1 - alpha) + (1 - pT) * alpha
        }

        // Regime
        val longAcc = if (cumTotal > 0) cumCorrect / cumTotal else 0.5

        val first20End = minOf(20, n)
        var first20T = 0
        for (i in 0 until first20End) if (h[i].sum >= T_THRESHOLD) first20T++
        val first20Rate = if (first20End > 0) first20T.toDouble() / first20End else 0.5

        val last20Start = maxOf(0, n - 20)
        var last20T = 0
        var last20N = 0
        for (i in last20Start until n) {
            if (h[i].sum >= T_THRESHOLD) last20T++
            last20N++
        }
        val last20Rate = if (last20N > 0) last20T.toDouble() / last20N else 0.5

        val regimeShiftScore = abs(last20Rate - first20Rate)
        val regimeChange = n > 40 && (
            regimeShiftScore > REGIME_STRONG ||
            (cumTotal > 100 && recentAcc < longAcc - REGIME_DROP)
        )

        if (regimeChange) pT = pT * 0.70 + 0.5 * 0.30

        pT = pT.coerceIn(0.02, 0.98)
        val pX = 1.0 - pT

        // ============================================================
        // BestSum convolution
        // ============================================================
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

        // Streak
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

        // Best/worst expert
        var bestIdx = 0; var worstIdx = 0
        var bestA = -1.0; var worstA = 2.0
        for (e in 0 until N_EXPERTS) {
            val a = if (expertTotal[e] > 0)
                expertCorrect[e].toDouble() / expertTotal[e] else 0.0
            if (a > bestA) { bestA = a; bestIdx = e }
            if (a < worstA) { worstA = a; worstIdx = e }
        }

        val crowdRate = cT_X / (cT_T + cT_X)
        val contrarianAdj = crowdRate - 0.5

        // ============================================================
        // TIME-AWARE METRICS
        // ============================================================
        val now = System.currentTimeMillis()
        val latestTs = h[n - 1].timestamp
        val dataAgeHours = (now - latestTs) / 3_600_000.0

        val sessionGapHours = if (n >= 2) {
            (h[n - 1].timestamp - h[n - 2].timestamp) / 3_600_000.0
        } else 0.0

        // Đếm size session hiện tại (ngược từ cuối)
        var currentSessionSize = 1
        for (i in n - 2 downTo 0) {
            val gap = h[i + 1].timestamp - h[i].timestamp
            if (gap > SESSION_GAP_MS) break
            currentSessionSize++
        }

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
            dataAgeHours = dataAgeHours,
            sessionGapHours = sessionGapHours,
            currentSessionSize = currentSessionSize,
            numSessions = numSessions,
            lastShrinkFactor = lastShrinkFactor
        )
    }
}
