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
    private val selected = IntArray(3) { 1 }
    private var currentPrediction: Predictor.Prediction? = null
    private var crowdChoice: Char = '?'

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

    /** Tạo 6 nút 1..6 trong 1 MaterialButtonToggleGroup với màu rõ ràng. */
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

        val items = listOf("T" to 'T', "X" to 'X', "Không chắc" to '?')

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

    private fun onConfirm() {
        val d1 = selected[0]; val d2 = selected[1]; val d3 = selected[2]
        val sum = d1 + d2 + d3
        val pred = currentPrediction
        val curCrowd = crowdChoice

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val wasT = sum >= 10
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
                if (c > Predictor.WINDOW) dao.deleteOldest(c - Predictor.WINDOW)
            }

            // Reset về "Không chắc"
            crowdChoice = '?'
            if (b.tgCrowd.childCount >= 3) {
                b.tgCrowd.check(b.tgCrowd.getChildAt(2).id)
            }

            val status = if (pred == null) "" else {
                val tOk = if (pred!!.nextIsT == (sum >= 10)) "✓" else "✗"
                val sOk = if (pred!!.bestSum == sum) "✓" else "✗"
                "  |  T/X $tOk  điểm $sOk"
            }
            b.tvLastSaved.text =
                "Đã lưu: $d1 - $d2 - $d3 = $sum  (${Predictor.classify(sum)})$status"
            refreshPrediction()
        }
    }

    private fun refreshPrediction() {
        lifecycleScope.launch {
            val history = withContext(Dispatchers.IO) {
                dao.lastNDesc(Predictor.WINDOW).asReversed()
            }
            val total = withContext(Dispatchers.IO) { dao.count() }
            val pred = withContext(Dispatchers.Default) { Predictor.predict(history) }
            currentPrediction = pred
            render(pred, total)
        }
    }

    private fun render(p: Predictor.Prediction?, total: Int) {
        if (p == null) {
            b.tvMainClass.text = "—"
            b.tvBarT.text = ""; b.tvBarX.text = ""
            b.tvLegend.text = "Chưa có dữ liệu. Nhập ít nhất 1 lần."
            b.tvSumPred.text = ""; b.tvTopSums.text = ""; b.tvSample.text = ""
            b.tvAccuracy.text = ""; b.tvAdjust.text = ""
            b.tvStreakBanner.visibility = View.GONE
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

        val lp1 = b.barT.layoutParams as LinearLayout.LayoutParams
        lp1.weight = p.probT.toFloat(); b.barT.layoutParams = lp1
        val lp2 = b.barX.layoutParams as LinearLayout.LayoutParams
        lp2.weight = p.probX.toFloat(); b.barX.layoutParams = lp2

        b.tvBarT.text = "T ${"%.0f%%".format(tPct)}"
        b.tvBarX.text = "X ${"%.0f%%".format(xPct)}"
        b.tvLegend.text =
            "T = 10–18   ·   X = 3–9   ·   lần cuối = ${p.lastSum} (${Predictor.classify(p.lastSum)})"

        b.tvSumPred.text = buildString {
            append("Tổng khả năng nhất: ")
            append("${p.bestSum}  (${"%.1f%%".format(p.bestSumProb * 100)})\n")
            append("Bộ 3 xúc xắc: ")
            append(p.dieProbs.map { it.maxByOrNull { e -> e.value }!!.key })
        }

        b.tvTopSums.text = "Top tổng: " + p.topSums.joinToString("  ") {
            "${it.first} (${"%.0f%%".format(it.second * 100)})"
        }

        if (p.feedbackSamples > 0) {
            b.tvAccuracy.text = buildString {
                append("📈 Độ chính xác ${p.feedbackSamples} lần gần nhất:\n")
                append("  • T/X:  ${"%.1f%%".format(p.accClassRecent * 100)}")
                append("   (chuỗi đúng liên tiếp: ${p.streakClass})\n")
                append("  • Điểm: ${"%.1f%%".format(p.accSumRecent * 100)}")
                append("   (chuỗi đúng liên tiếp: ${p.streakSum})")
            }
        } else {
            b.tvAccuracy.text =
                "📈 Chưa có dữ liệu đánh giá — nhập thêm để hệ thống tự học."
        }

        // Khối điều chỉnh + adversarial
        val adj = buildString {
            append("⚙ bias = ")
            append("%+.1f%%".format(p.biasT * 100))
            append("  ·  k = ")
            append("%.2f".format(p.adaptiveK))
            append("\n📊 Markov b2 pT = ")
            append("${"%.1f%%".format(p.markov2ProbT * 100)}")
            append("  ·  Raw pT = ")
            append("${"%.1f%%".format(p.rawPT * 100)}")
            if (p.crowdAccuracy > 0) {
                append("\n👥 Đám đông đúng = ")
                append("${"%.0f%%".format(p.crowdAccuracy * 100)}")
                append("  ·  Contrarian = ")
                append("%+.1f%%".format(p.contrarianAdj * 100))
            }
            if (p.adversarialMode) {
                append("\n🔴 CHẾ ĐỘ ĐỐI KHÁNG — model bị chơi, đã đảo dự đoán")
            }
        }
        b.tvAdjust.text = adj

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

        b.tvSample.text =
            "Mẫu phân tích: ${p.sampleCount} / ${Predictor.WINDOW}   (tổng DB: $total)"
    }
}
