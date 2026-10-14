package com.example.dicepredictor

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.dicepredictor.databinding.ActivityMainBinding
import com.example.dicepredictor.db.AppDatabase
import com.example.dicepredictor.db.DiceDao
import com.example.dicepredictor.db.DiceResult
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var dao: DiceDao

    /** Giá trị đang chọn của 3 xúc xắc (1..6). Mặc định 1. */
    private val selected = IntArray(3) { 1 }

    /** Dự đoán đang hiển thị — sẽ được lưu kèm khi user xác nhận. */
    private var currentPrediction: Predictor.Prediction? = null

    /** Lựa chọn đám đông hiện tại: 'T' / 'X' / '?' (không chắc). */
    private var crowdChoice: Char = '?'

    // ============================================================
    // VÒNG ĐỜI
    // ============================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        dao = AppDatabase.get(this).diceDao()

        buildRow(b.tg1, 0)
        buildRow(b.tg2, 1)
        buildRow(b.tg3, 2)
        buildCrowdRow()

        b.btnConfirm.setOnClickListener { onConfirm() }
        b.btnDashboard.setOnClickListener {
            startActivity(Intent(this, DashboardActivity::class.java))
        }

        refreshPrediction()
    }

    override fun onResume() {
        super.onResume()
        refreshPrediction()
    }

    // ============================================================
    // UI BUILDERS
    // ============================================================

    /** Tạo 6 nút 1..6 trong 1 MaterialButtonToggleGroup. */
    private fun buildRow(tg: MaterialButtonToggleGroup, rowIdx: Int) {
        val textStates = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf()
        )
        val textCSL = ColorStateList(
            textStates,
            intArrayOf(Color.WHITE, Color.parseColor("#222222"))
        )
        val bgCSL = ColorStateList(
            textStates,
            intArrayOf(Color.parseColor("#1A73E8"), Color.WHITE)
        )
        val strokeCSL = ColorStateList.valueOf(Color.parseColor("#1A73E8"))

        for (v in 1..6) {
            val btn = MaterialButton(
                this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                id = View.generateViewId()
                text = v.toString()
                textSize = 20f
                tag = v
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                insetTop = 0
                insetBottom = 0
                setTextColor(textCSL)
                backgroundTintList = bgCSL
                strokeColor = strokeCSL
                strokeWidth = 2
                cornerRadius = 8
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginStart = 3; marginEnd = 3 }
            }
            tg.addView(btn)
            if (v == 1) tg.check(btn.id)
        }
        tg.addOnButtonCheckedListener { grp, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val btn = grp.findViewById<MaterialButton>(checkedId)
            selected[rowIdx] = btn.tag as Int
        }
    }

    /** 3 nút đám đông: T / X / Không chắc. */
    private fun buildCrowdRow() {
        val textStates = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf()
        )
        val textCSL = ColorStateList(
            textStates,
            intArrayOf(Color.WHITE, Color.parseColor("#222222"))
        )
        val bgCSL = ColorStateList(
            textStates,
            intArrayOf(Color.parseColor("#D93025"), Color.WHITE)
        )
        val strokeCSL = ColorStateList.valueOf(Color.parseColor("#D93025"))

        val items = listOf(
            "T" to 'T',
            "X" to 'X',
            "Không chắc" to '?'
        )

        for ((label, value) in items) {
            val btn = MaterialButton(
                this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                id = View.generateViewId()
                text = label
                textSize = 15f
                tag = value
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                insetTop = 0
                insetBottom = 0
                setTextColor(textCSL)
                backgroundTintList = bgCSL
                strokeColor = strokeCSL
                strokeWidth = 2
                cornerRadius = 8
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginStart = 3; marginEnd = 3 }
            }
            b.tgCrowd.addView(btn)
            if (value == '?') b.tgCrowd.check(btn.id)
        }

        b.tgCrowd.addOnButtonCheckedListener { grp, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val btn = grp.findViewById<MaterialButton>(checkedId)
            crowdChoice = btn.tag as Char
        }
    }

    // ============================================================
    // XÁC NHẬN KẾT QUẢ
    // ============================================================

    private fun onConfirm() {
        val d1 = selected[0]
        val d2 = selected[1]
        val d3 = selected[2]
        val sum = d1 + d2 + d3
        val pred = currentPrediction
        val curCrowd = crowdChoice

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val wasT = sum >= Predictor.T_THRESHOLD
                val hasPred = pred != null

                val row = DiceResult(
                    d1 = d1, d2 = d2, d3 = d3, sum = sum,
                    hasPred = hasPred,
                    predSum = pred?.bestSum ?: 0,
                    predWasT = pred?.nextIsT ?: false,
                    predProbT = pred?.probT ?: 0.0,
                    sumCorrect = hasPred && pred!!.bestSum == sum,
                    classCorrect = hasPred && pred!!.nextIsT == wasT,
                    crowdChoice = curCrowd,
                    crowdStrength = 0
                )
                dao.insert(row)

                val c = dao.count()
                if (c > Predictor.WINDOW) {
                    dao.deleteOldest(c - Predictor.WINDOW)
                }
            }

            // Reset đám đông về "Không chắc"
            crowdChoice = '?'
            if (b.tgCrowd.childCount >= 3) {
                b.tgCrowd.check(b.tgCrowd.getChildAt(2).id)
            }

            val status = if (pred == null) {
                ""
            } else {
                val tOk = if (pred.nextIsT == (sum >= Predictor.T_THRESHOLD)) "✓" else "✗"
                val sOk = if (pred.bestSum == sum) "✓" else "✗"
                "  |  T/X $tOk  điểm $sOk"
            }
            b.tvLastSaved.text =
                "Đã lưu: $d1 - $d2 - $d3 = $sum  (${Predictor.classify(sum)})$status"

            refreshPrediction()
        }
    }

    // ============================================================
    // REFRESH DỰ ĐOÁN
    // ============================================================

    private fun refreshPrediction() {
        lifecycleScope.launch {
            val history = withContext(Dispatchers.IO) {
                dao.lastNDesc(Predictor.WINDOW).asReversed()
            }
            val total = withContext(Dispatchers.IO) { dao.count() }
            val pred = withContext(Dispatchers.Default) {
                Predictor.predict(history)
            }
            currentPrediction = pred
            render(pred, total)
        }
    }

    // ============================================================
    // RENDER
    // ============================================================

    private fun render(p: Predictor.Prediction?, total: Int) {
        if (p == null) {
            b.tvMainClass.text = "—"
            b.tvBarT.text = ""
            b.tvBarX.text = ""
            b.tvLegend.text = "Chưa có dữ liệu. Nhập ít nhất 1 lần."
            b.tvSumPred.text = ""
            b.tvTopSums.text = ""
            b.tvSample.text = ""
            b.tvAccuracy.text = ""
            b.tvAdjust.text = ""
            b.tvStreakBanner.visibility = View.GONE
            b.tvTimeInfo.visibility = View.GONE
            return
        }

        val tPct = p.probT * 100
        val xPct = p.probX * 100
        val main = if (p.nextIsT) "T" else "X"
        val mainProb = if (p.nextIsT) tPct else xPct

        b.tvMainClass.text = "→ $main   ${"%.1f%%".format(mainProb)}"
        b.tvMainClass.setTextColor(
            if (p.nextIsT) 0xFF1E8E3E.toInt() else 0xFFD93025.toInt()
        )

        // Bar T | X
        val lp1 = b.barT.layoutParams as LinearLayout.LayoutParams
        lp1.weight = p.probT.toFloat()
        b.barT.layoutParams = lp1
        val lp2 = b.barX.layoutParams as LinearLayout.LayoutParams
        lp2.weight = p.probX.toFloat()
        b.barX.layoutParams = lp2

        b.tvBarT.text = "T ${"%.0f%%".format(tPct)}"
        b.tvBarX.text = "X ${"%.0f%%".format(xPct)}"
        b.tvLegend.text =
            "T = 11–18   ·   X = 3–10   ·   lần cuối = ${p.lastSum} (${Predictor.classify(p.lastSum)})"

        b.tvSumPred.text = buildString {
            append("Tổng khả năng nhất: ")
            append("${p.bestSum}  (${"%.1f%%".format(p.bestSumProb * 100)})\n")
            append("Bộ 3 xúc xắc: ")
            append(p.dieProbs.map { it.maxByOrNull { e -> e.value }!!.key })
        }

        b.tvTopSums.text = "Top tổng: " + p.topSums.joinToString("  ") {
            "${it.first} (${"%.0f%%".format(it.second * 100)})"
        }

        // Khối accuracy + ensemble
        b.tvAccuracy.text = buildString {
            append("📈 Accuracy ensemble: ")
            append("${"%.1f%%".format(p.ensembleAcc * 100)}\n")
            append("⚡ Accuracy gần đây:  ")
            append("${"%.1f%%".format(p.recentAcc * 100)}\n")
            append("🏆 Chuyên gia tốt: ${p.bestExpertName} ")
            append("(${"%.0f%%".format(p.bestExpertAcc * 100)})\n")
            append("⚠️  Chuyên gia tệ: ${p.worstExpertName}\n")
            append("📊 Streak T/X: ${p.streakClass}  ·  Streak điểm: ${p.streakSum}")
        }

        // Khối điều chỉnh + adversarial + reversal signals
        val adj = buildString {
            append("⚙ Raw pT = ${"%.1f%%".format(p.rawPT * 100)}")
            append("  ·  bias = ${"%+.1f%%".format(p.biasT * 100)}\n")
            append("📉 Corr dự đoán/kết quả = ${"%+.2f".format(p.adversarialCorr)}\n")
            if (p.crowdRate > 0) {
                append("👥 P(X | đám đông chọn T) = ")
                append("${"%.0f%%".format(p.crowdRate * 100)}")
                append("  ·  Contrarian = ")
                append("${"%+.1f%%".format(p.contrarianAdj * 100)}\n")
            }

            // Top 3 chuyên gia theo trọng số
            val top3 = p.expertWeights.sortedByDescending { it.second }.take(3)
            append("🥇 Top 3: ")
            append(
                top3.joinToString("  ") {
                    "${it.first}(${"%.0f%%".format(it.second * 100)})"
                }
            )

            // Reversal signals
            append("\n🔄 Regime30: ${"%.0f%%".format(p.regime30T * 100)} T")
            if (p.streak5Signal > 0) {
                append("  ·  Streak5: ${"%.2f".format(p.streak5Signal)}")
            }

            if (p.regimeChange) {
                append("\n⚠️ PHÁT HIỆN ĐỔI REGIME — độ tin cậy đã giảm")
            }
            if (p.adversarialMode) {
                append("\n🔴 CHẾ ĐỘ ĐỐI KHÁNG — model đang bị chơi, đã đảo dự đoán")
            }
            if (p.streak5Signal > 0.5) {
                append("\n🔶 Streak dài — cảnh báo đảo chiều cao")
            }
        }
        b.tvAdjust.text = adj

        // Banner streak
        val maxStreak = maxOf(p.streakClass, p.streakSum)
        if (maxStreak >= Predictor.STREAK_THRESHOLD) {
            val which = when {
                p.streakClass >= Predictor.STREAK_THRESHOLD &&
                    p.streakSum >= Predictor.STREAK_THRESHOLD -> "T/X + Điểm"
                p.streakClass >= Predictor.STREAK_THRESHOLD -> "T/X"
                else -> "Điểm"
            }
            b.tvStreakBanner.visibility = View.VISIBLE
            b.tvStreakBanner.text =
                "🔥 TUYỆT VỜI! Dự đoán $which chính xác $maxStreak lần liên tiếp!"
        } else {
            b.tvStreakBanner.visibility = View.GONE
        }

        // Time-aware info
        val timeInfo = buildString {
            val ageStr = when {
                p.dataAgeHours < 0.05 -> "vừa xong"
                p.dataAgeHours < 1.0 ->
                    "${"%.0f".format(p.dataAgeHours * 60)} phút trước"
                p.dataAgeHours < 24 ->
                    "${"%.1f".format(p.dataAgeHours)} giờ trước"
                else ->
                    "${"%.1f".format(p.dataAgeHours / 24)} ngày trước"
            }
            append("⏱ Dữ liệu cuối: $ageStr\n")

            append("📦 Session hiện tại: ${p.currentSessionSize} ván")
            append("  ·  Tổng session trong mẫu: ${p.numSessions}\n")

            if (p.sessionGapHours > 0.5) {
                val gapStr = if (p.sessionGapHours < 24)
                    "${"%.1f".format(p.sessionGapHours)} giờ"
                else "${"%.1f".format(p.sessionGapHours / 24)} ngày"
                append("⏸ Nghỉ giữa 2 ván: $gapStr")
            }
            if (p.lastShrinkFactor < 0.999) {
                append("  ·  Shrink session cuối: ")
                append("${"%.0f%%".format(p.lastShrinkFactor * 100)}")
            }
        }
        b.tvTimeInfo.text = timeInfo
        b.tvTimeInfo.visibility = View.VISIBLE

        b.tvSample.text =
            "Mẫu phân tích: ${p.sampleCount} / ${Predictor.WINDOW}   (tổng DB: $total)"
    }
}
