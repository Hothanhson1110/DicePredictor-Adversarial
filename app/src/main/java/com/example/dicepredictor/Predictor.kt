package com.example.dicepredictor

import com.example.dicepredictor.db.DiceResult

/**
 * Predictor tự học + chống đối kháng (adversarial).
 *
 * Các tầng logic:
 *  1. Feedback loop  — bias correction từ dự đoán cũ
 *  2. Recency weight — trọng số mẫu mới > cũ
 *  3. Distribution   — phân bố mặt xúc xắc + convolution tổng
 *  4. Markov bậc 1   — trên trạng thái T/X
 *  5. Markov bậc 2   — trên 2 trạng thái gần nhất (TT/TX/XT/XX)
 *  6. Contrarian     — điều chỉnh khi nhà cái phá đám đông
 *  7. Adversarial    — tự đảo dự đoán khi model đang bị "chơi"
 */
object Predictor {

    const val WINDOW = 10_000
    private val DECAY: Double = Math.pow(0.02, 1.0 / WINDOW)
    private const val LAPLACE = 0.5
    private const val FEEDBACK_WINDOW = 200
    const val STREAK_THRESHOLD = 5

    private const val CONTRARIAN_WEIGHT = 0.35
    private const val ADVERSARIAL_THRESHOLD = 0.40
    private const val ADVERSARIAL_MIN_SAMPLES = 40

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

        // Tự học
        val biasT: Double,
        val adaptiveK: Double,
        val accClassRecent: Double,
        val accSumRecent: Double,
        val feedbackSamples: Int,
        val streakClass: Int,
        val streakSum: Int,

        // Adversarial
        val contrarianAdj: Double,
        val crowdAccuracy: Double,
        val adversarialMode: Boolean,
        val flipped: Boolean,
        val rawPT: Double,
        val markov2ProbT: Double
    )

    fun classify(sum: Int): Char = if (sum >= 10) 'T' else 'X'

    fun predict(all: List<DiceResult>): Prediction? {
        if (all.isEmpty()) return null

        val h = if (all.size > WINDOW) all.subList(all.size - WINDOW, all.size) else all
        val n = h.size

        // ============================================================
        // BƯỚC 1: Feedback loop
        // ============================================================
        var sumErr = 0.0
        var cntPred = 0
        var correctClass = 0
        var correctSum = 0

        var crowdPredictions = 0
        var crowdWins = 0

        for (i in n - 1 downTo 0) {
            if (cntPred >= FEEDBACK_WINDOW) break
            val r = h[i]

            if (r.crowdChoice != '?') {
                crowdPredictions++
                val outcomeIsT = r.sum >= 10
                val crowdWasT = r.crowdChoice == 'T'
                if (outcomeIsT != crowdWasT) crowdWins++
            }

            if (!r.hasPred) continue
            val outcomeT = if (r.sum >= 10) 1.0 else 0.0
            sumErr += (outcomeT - r.predProbT)
            if (r.classCorrect) correctClass++
            if (r.sumCorrect) correctSum++
            cntPred++
        }

        val biasT = if (cntPred > 0) (sumErr / cntPred).coerceIn(-0.30, 0.30) else 0.0
        val accClass = if (cntPred > 0) correctClass.toDouble() / cntPred else 0.5
        val accSum = if (cntPred > 0) correctSum.toDouble() / cntPred else 0.0
        val crowdLossRate = if (crowdPredictions > 0)
            crowdWins.toDouble() / crowdPredictions else 0.5

        // ============================================================
        // BƯỚC 2: Recency weights (không dùng pow)
        // ============================================================
        val w = DoubleArray(n)
        w[n - 1] = 1.0
        for (i in n - 2 downTo 0) w[i] = w[i + 1] * DECAY

        var wSum = 0.0
        for (i in 0 until n) wSum += w[i]
        if (wSum <= 0.0) return null

        // ============================================================
        // BƯỚC 3: Phân bố mặt xúc xắc
        // ============================================================
        val dieCount = Array(3) { DoubleArray(7) { LAPLACE } }
        val dieSum = DoubleArray(3) { 6.0 * LAPLACE }
        for (i in 0 until n) {
            val wi = w[i]; val r = h[i]
            dieCount[0][r.d1] += wi; dieSum[0] += wi
            dieCount[1][r.d2] += wi; dieSum[1] += wi
            dieCount[2][r.d3] += wi; dieSum[2] += wi
        }
        val p1 = DoubleArray(7); val p2 = DoubleArray(7); val p3 = DoubleArray(7)
        for (v in 1..6) {
            p1[v] = dieCount[0][v] / dieSum[0]
            p2[v] = dieCount[1][v] / dieSum[1]
            p3[v] = dieCount[2][v] / dieSum[2]
        }

        // ============================================================
        // BƯỚC 4: Convolution — phân phối tổng
        // ============================================================
        val sumProbs = DoubleArray(19)
        for (a in 1..6) {
            val pa = p1[a]
            for (b in 1..6) {
                val pab = pa * p2[b]
                for (c in 1..6) sumProbs[a + b + c] += pab * p3[c]
            }
        }

        // ============================================================
        // BƯỚC 5: Markov bậc 1 (T/X)
        // ============================================================
        var tt = 0.0; var tx = 0.0; var xt = 0.0; var xx = 0.0
        var baseT = 0.0
        for (i in 0 until n) if (h[i].sum >= 10) baseT += w[i]
        baseT /= wSum

        for (i in 0 until n - 1) {
            val cur = h[i].sum >= 10
            val nxt = h[i + 1].sum >= 10
            val wt = w[i + 1]
            if (cur) { if (nxt) tt += wt else tx += wt }
            else     { if (nxt) xt += wt else xx += wt }
        }
        val lastIsT = h[n - 1].sum >= 10
        val m1T: Double
        if (lastIsT) {
            val s = tt + tx
            m1T = if (s > 0) tt / s else baseT
        } else {
            val s = xt + xx
            m1T = if (s > 0) xt / s else baseT
        }

        // ============================================================
        // BƯỚC 6: Markov bậc 2 — state = (prev2, prev1)
        //         TT=3, TX=2, XT=1, XX=0
        // ============================================================
        val m2count = Array(4) { DoubleArray(2) }
        for (i in 2 until n) {
            val prev2 = h[i - 2].sum >= 10
            val prev1 = h[i - 1].sum >= 10
            val state = (if (prev2) 2 else 0) + (if (prev1) 1 else 0)
            val nextIsT = h[i].sum >= 10
            m2count[state][if (nextIsT) 1 else 0] += w[i]
        }
        val curState = if (n >= 2) {
            val prev2 = h[n - 2].sum >= 10
            val prev1 = h[n - 1].sum >= 10
            (if (prev2) 2 else 0) + (if (prev1) 1 else 0)
        } else 0

        val m2Total = m2count[curState][0] + m2count[curState][1]
        val m2T: Double = if (m2Total > 1.0) m2count[curState][1] / m2Total else baseT

        // ============================================================
        // BƯỚC 7: Trộn Markov b1 + b2 + base
        // ============================================================
        val m2Weight = if (n >= 200) 0.40 else (n / 500.0).coerceIn(0.0, 0.40)
        val markovCombined = m1T * (1 - m2Weight) + m2T * m2Weight

        var k = if (n >= 60) 1.0 else n / 60.0
        if (cntPred >= 30) {
            k = when {
                accClass < 0.45 -> k * 0.60
                accClass < 0.50 -> k * 0.80
                accClass > 0.60 -> (k * 1.15).coerceAtMost(1.0)
                else            -> k
            }
        }

        val rawPT = k * markovCombined + (1 - k) * baseT

        // ============================================================
        // BƯỚC 8: Contrarian adjustment
        // ============================================================
        val crowdBias = (crowdLossRate - 0.5).coerceIn(-0.4, 0.4)
        val contrarianAdj = crowdBias * CONTRARIAN_WEIGHT

        // ============================================================
        // BƯỚC 9: Bias correction
        // ============================================================
        var pT = (rawPT + biasT + contrarianAdj).coerceIn(0.05, 0.95)

        // ============================================================
        // BƯỚC 10: Adversarial flip
        // ============================================================
        var adversarialMode = false
        var flipped = false
        if (cntPred >= ADVERSARIAL_MIN_SAMPLES && accClass < ADVERSARIAL_THRESHOLD) {
            adversarialMode = true
            pT = 1.0 - pT
            flipped = true
        }

        val pX = 1.0 - pT

        // ============================================================
        // BƯỚC 11: Top tổng
        // ============================================================
        val ordered = (3..18).sortedByDescending { sumProbs[it] }
        val topSums = ordered.take(5).map { it to sumProbs[it] }
        val bestSum = ordered.first()
        val bestSumProb = sumProbs[bestSum]

        val dieProbsMaps = (0..2).map { d ->
            HashMap<Int, Double>(8).apply {
                for (v in 1..6) put(v, dieCount[d][v] / dieSum[d])
            }
        }

        // ============================================================
        // BƯỚC 12: Streak
        // ============================================================
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

        return Prediction(
            sampleCount = n,
            lastSum = h[n - 1].sum,
            nextIsT = pT >= pX,
            probT = pT, probX = pX,
            bestSum = bestSum, bestSumProb = bestSumProb,
            topSums = topSums, dieProbs = dieProbsMaps,
            biasT = biasT,
            adaptiveK = k,
            accClassRecent = accClass,
            accSumRecent = accSum,
            feedbackSamples = cntPred,
            streakClass = streakClass,
            streakSum = streakSum,
            contrarianAdj = contrarianAdj,
            crowdAccuracy = if (crowdPredictions > 0)
                (crowdPredictions - crowdWins).toDouble() / crowdPredictions else 0.0,
            adversarialMode = adversarialMode,
            flipped = flipped,
            rawPT = rawPT,
            markov2ProbT = m2T
        )
    }
}
