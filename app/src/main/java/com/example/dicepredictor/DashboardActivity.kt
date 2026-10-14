package com.example.dicepredictor

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.dicepredictor.databinding.ActivityDashboardBinding
import com.example.dicepredictor.databinding.DialogEditResultBinding
import com.example.dicepredictor.databinding.ItemResultBinding
import com.example.dicepredictor.db.AppDatabase
import com.example.dicepredictor.db.DiceDao
import com.example.dicepredictor.db.DiceResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DashboardActivity : AppCompatActivity() {

    private lateinit var b: ActivityDashboardBinding
    private lateinit var dao: DiceDao
    private val adapter = Adapter()

    private var lastExportFile: File? = null

    // ============================================================
    // VÒNG ĐỜI
    // ============================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(b.root)

        dao = AppDatabase.get(this).diceDao()

        b.rvResults.layoutManager = LinearLayoutManager(this)
        b.rvResults.adapter = adapter

        // Tab switcher — style cho 2 nút
        setupTabs()

        b.btnBack.setOnClickListener { finish() }
        b.btnRefresh.setOnClickListener { reload() }

        b.btnExportCsv.setOnClickListener { doExport(csv = true) }
        b.btnExportJson.setOnClickListener { doExport(csv = false) }
        b.btnShareLast.setOnClickListener { shareLast() }

        b.btnDelete100.setOnClickListener {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { dao.deleteLast(100) }
                reload()
            }
        }
        b.btnClearAll.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Xoá toàn bộ database?")
                .setMessage("Hành động này không thể hoàn tác.")
                .setPositiveButton("Xoá") { _, _ ->
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            dao.clearAll()
                            AppDatabase.vacuum(
                                AppDatabase.get(this@DashboardActivity)
                            )
                        }
                        lastExportFile = null
                        reload()
                    }
                }
                .setNegativeButton("Huỷ", null)
                .show()
        }

        reload()
    }

    // ============================================================
    // TAB SWITCHER
    // ============================================================

    private fun setupTabs() {
        val textStates = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf()
        )
        val textCSL = ColorStateList(
            textStates,
            intArrayOf(Color.WHITE, Color.parseColor("#1A73E8"))
        )
        val bgCSL = ColorStateList(
            textStates,
            intArrayOf(Color.parseColor("#1A73E8"), Color.WHITE)
        )

        b.btnTabAnalysis.setTextColor(textCSL)
        b.btnTabAnalysis.backgroundTintList = bgCSL
        b.btnTabAnalysis.isAllCaps = false

        b.btnTabHistory.setTextColor(textCSL)
        b.btnTabHistory.backgroundTintList = bgCSL
        b.btnTabHistory.isAllCaps = false

        // Mặc định chọn tab Phân tích
        b.tgTabs.check(b.btnTabAnalysis.id)

        b.tgTabs.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                b.btnTabAnalysis.id -> showTab(analysis = true)
                b.btnTabHistory.id -> showTab(analysis = false)
            }
        }
    }

    private fun showTab(analysis: Boolean) {
        if (analysis) {
            b.scrollStats.visibility = View.VISIBLE
            b.rvResults.visibility = View.GONE
        } else {
            b.scrollStats.visibility = View.GONE
            b.rvResults.visibility = View.VISIBLE
        }
    }

    // ============================================================
    // RELOAD
    // ============================================================

    private fun reload() {
        lifecycleScope.launch {
            val history = withContext(Dispatchers.IO) {
                dao.lastNDesc(Predictor.WINDOW).asReversed()
            }
            val pred = withContext(Dispatchers.Default) {
                Predictor.predict(history)
            }
            adapter.submitList(history.asReversed())
            renderStats(history, pred)
        }
    }

    // ============================================================
    // STATS PANEL
    // ============================================================

    private fun renderStats(h: List<DiceResult>, p: Predictor.Prediction?) {
        if (h.isEmpty()) {
            b.tvStats.text = "Database trống."
            return
        }

        val totalT = h.count { Predictor.classify(it.sum) == 'T' }
        val totalX = h.size - totalT
        val tPct = totalT * 100.0 / h.size
        val xPct = totalX * 100.0 / h.size

        val sumHist = IntArray(19)
        h.forEach { sumHist[it.sum]++ }
        val topSums = (3..18)
            .sortedByDescending { sumHist[it] }
            .take(6)
            .joinToString("  ") { "$it:${sumHist[it]}" }

        val faces = Array(3) { IntArray(7) }
        h.forEach {
            faces[0][it.d1]++
            faces[1][it.d2]++
            faces[2][it.d3]++
        }
        fun facesLine(a: IntArray) =
            (1..6).joinToString(" ") { "$it:${a[it]}" }

        val crowdTotal = h.count { it.crowdChoice != '?' }
        val crowdWrong = h.count { r ->
            if (r.crowdChoice == '?') false
            else (r.crowdChoice == 'T') != (r.sum >= Predictor.T_THRESHOLD)
        }

        b.tvStats.text = buildString {
            append("Tổng mẫu: ${h.size} / ${Predictor.WINDOW}\n")
            append("T (11-18): $totalT   (${"%.1f%%".format(tPct)})\n")
            append("X (3-10) : $totalX   (${"%.1f%%".format(xPct)})\n\n")

            if (p != null) {
                append("Lần cuối: ${h.last().d1}-${h.last().d2}-${h.last().d3} = ")
                append("${h.last().sum} (${Predictor.classify(h.last().sum)})\n")
                append("Dự đoán tiếp: ${if (p.nextIsT) "T" else "X"} ")
                append("(T ${"%.1f%%".format(p.probT * 100)}")
                append(" / X ${"%.1f%%".format(p.probX * 100)})\n")
                append("Tổng khả năng nhất: ${p.bestSum}\n")
                append("Mẫu dùng để tính: ${p.sampleCount}/${Predictor.WINDOW}\n")

                append("\n--- Ensemble (Hedge) ---\n")
                append("Accuracy dài hạn: ")
                append("${"%.1f%%".format(p.ensembleAcc * 100)}\n")
                append("Accuracy gần đây: ")
                append("${"%.1f%%".format(p.recentAcc * 100)}\n")
                append("Chuyên gia tốt nhất: ${p.bestExpertName} ")
                append("(${"%.1f%%".format(p.bestExpertAcc * 100)})\n")
                append("Chuyên gia tệ nhất: ${p.worstExpertName}\n")
                append("Corr dự đoán/kết quả: ")
                append("${"%+.2f".format(p.adversarialCorr)}\n")
                append("Bias: ${"%+.1f%%".format(p.biasT * 100)}\n")
                append("Streak T/X: ${p.streakClass}")
                append("  ·  Streak điểm: ${p.streakSum}\n")

                append("\n--- Tín hiệu đảo chiều ---\n")
                append("Regime30: ${"%.1f%%".format(p.regime30T * 100)} T")
                append("  (cường độ ${"%.2f".format(p.regime30Signal)})\n")
                append("Streak5 signal: ")
                append("${"%.2f".format(p.streak5Signal)}\n")

                if (p.streak5Signal > 0.5) {
                    append("🔶 Cảnh báo: streak dài → khả năng đảo cao\n")
                }
                if (p.regime30Signal > 0.30) {
                    append("🔶 Regime30 nghiêng mạnh → có thể đảo\n")
                }

                append("\n--- Thời gian ---\n")
                val ageStr = when {
                    p.dataAgeHours < 0.05 -> "vừa xong"
                    p.dataAgeHours < 1.0 ->
                        "${"%.0f".format(p.dataAgeHours * 60)} phút trước"
                    p.dataAgeHours < 24 ->
                        "${"%.1f".format(p.dataAgeHours)} giờ trước"
                    else ->
                        "${"%.1f".format(p.dataAgeHours / 24)} ngày trước"
                }
                append("Dữ liệu cuối: $ageStr\n")
                append("Session hiện tại: ${p.currentSessionSize} ván\n")
                append("Tổng session trong mẫu: ${p.numSessions}\n")

                if (p.sessionGapHours > 0.05) {
                    val gapStr = if (p.sessionGapHours < 24)
                        "${"%.2f".format(p.sessionGapHours)} giờ"
                    else "${"%.1f".format(p.sessionGapHours / 24)} ngày"
                    append("Nghỉ giữa 2 ván cuối: $gapStr\n")
                }
                if (p.lastShrinkFactor < 0.999) {
                    append("Shrink session gần nhất: ")
                    append("${"%.1f%%".format(p.lastShrinkFactor * 100)}\n")
                }
                append("Win20: ${"%.1f%%".format(p.win20T * 100)}")
                append("  ·  Win50: ${"%.1f%%".format(p.win50T * 100)}\n")
                append("(Half-life = 10h · Session gap = 200 phút)\n")

                append("\n--- Trọng số chuyên gia ---\n")
                p.expertWeights
                    .sortedByDescending { it.second }
                    .forEach { (name, w) ->
                        val padded = name.padEnd(9)
                        append("  $padded ${"%.1f%%".format(w * 100)}\n")
                    }

                if (p.regimeChange) {
                    append("\n⚠️ REGIME CHANGE đang diễn ra")
                    append(" (score ${"%.2f".format(p.regimeShiftScore)})\n")
                }
                if (p.adversarialMode) {
                    append("\n🔴 CHẾ ĐỘ ĐỐI KHÁNG đang BẬT\n")
                }
            }

            if (crowdTotal > 0) {
                append("\n--- Đám đông ---\n")
                append("Số lần có chọn: $crowdTotal\n")
                append("Nhà cái phá đám đông: $crowdWrong ")
                append("(${"%.0f%%".format(crowdWrong * 100.0 / crowdTotal)})\n")
            }

            append("\nPhân bố tổng (top 6):\n  $topSums\n")
            append("\nMặt X1: ${facesLine(faces[0])}\n")
            append("Mặt X2: ${facesLine(faces[1])}\n")
            append("Mặt X3: ${facesLine(faces[2])}\n")
        }
    }

    // ============================================================
    // EXPORT
    // ============================================================

    private fun doExport(csv: Boolean) {
        lifecycleScope.launch {
            try {
                val data = withContext(Dispatchers.IO) { dao.all() }
                if (data.isEmpty()) {
                    Toast.makeText(
                        this@DashboardActivity,
                        "Database trống — không có gì để xuất",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }
                val file = withContext(Dispatchers.IO) {
                    if (csv) ExportUtil.exportCsv(this@DashboardActivity, data)
                    else     ExportUtil.exportJson(this@DashboardActivity, data)
                }
                lastExportFile = file
                val sizeKb = file.length() / 1024
                Toast.makeText(
                    this@DashboardActivity,
                    "✅ Đã xuất ${data.size} dòng (${sizeKb} KB)\n" +
                        "📁 ${file.absolutePath}",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@DashboardActivity,
                    "❌ Lỗi xuất file: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun shareLast() {
        val f = lastExportFile
        if (f == null || !f.exists()) {
            Toast.makeText(
                this,
                "Chưa có file nào — hãy xuất CSV hoặc JSON trước",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        try {
            ExportUtil.shareFile(this, f)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "❌ Không chia sẻ được: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ============================================================
    // ADAPTER
    // ============================================================

    private inner class Adapter : ListAdapter<DiceResult, VH>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ib = ItemResultBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VH(ib)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(getItem(position), position)
        }
    }

    private inner class VH(val ib: ItemResultBinding) :
        RecyclerView.ViewHolder(ib.root) {

        fun bind(r: DiceResult, pos: Int) {
            ib.tvIdx.text = "#${pos + 1}"

            val marks = if (r.hasPred) {
                val t = if (r.classCorrect) "✓" else "✗"
                val s = if (r.sumCorrect) "✓" else "✗"
                "  [T/X $t · Điểm $s]"
            } else ""

            val crowdMark = if (r.crowdChoice != '?')
                "  👥${r.crowdChoice}" else ""

            ib.tvDice.text =
                "${r.d1} - ${r.d2} - ${r.d3}   =   ${r.sum}$marks$crowdMark"

            val cls = Predictor.classify(r.sum)
            ib.tvCls.text = cls.toString()
            ib.tvCls.setTextColor(
                if (cls == 'T') 0xFF1E8E3E.toInt() else 0xFFD93025.toInt()
            )

            ib.btnEdit.setOnClickListener { showEditDialog(r) }
            ib.btnDelete.setOnClickListener {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { dao.delete(r) }
                    reload()
                }
            }
        }
    }

    // ============================================================
    // EDIT DIALOG
    // ============================================================

    private fun showEditDialog(r: DiceResult) {
        val v = DialogEditResultBinding.inflate(layoutInflater)
        listOf(
            v.np1 to r.d1,
            v.np2 to r.d2,
            v.np3 to r.d3
        ).forEach { (np, v0) ->
            np.minValue = 1
            np.maxValue = 6
            np.value = v0
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Sửa kết quả")
            .setView(v.root)
            .setPositiveButton("Lưu") { _, _ ->
                val d1 = v.np1.value
                val d2 = v.np2.value
                val d3 = v.np3.value
                val newSum = d1 + d2 + d3

                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        dao.update(
                            r.copy(
                                d1 = d1, d2 = d2, d3 = d3, sum = newSum,
                                sumCorrect = if (r.hasPred)
                                    r.predSum == newSum else false,
                                classCorrect = if (r.hasPred)
                                    r.predWasT ==
                                        (newSum >= Predictor.T_THRESHOLD)
                                else false
                            )
                        )
                    }
                    reload()
                }
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    // ============================================================
    // DIFF
    // ============================================================

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<DiceResult>() {
            override fun areItemsTheSame(a: DiceResult, b: DiceResult): Boolean =
                a.id == b.id

            override fun areContentsTheSame(a: DiceResult, b: DiceResult): Boolean =
                a == b
        }
    }
}
