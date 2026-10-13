package com.example.dicepredictor

import android.os.Bundle
import android.view.LayoutInflater
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

    /** File export gần nhất — dùng cho nút Share. */
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

        // Header
        b.btnBack.setOnClickListener { finish() }
        b.btnRefresh.setOnClickListener { reload() }

        // Export
        b.btnExportCsv.setOnClickListener { doExport(csv = true) }
        b.btnExportJson.setOnClickListener { doExport(csv = false) }
        b.btnShareLast.setOnClickListener { shareLast() }

        // Xoá
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
    // RELOAD DATA
    // ============================================================

    private fun reload() {
        lifecycleScope.launch {
            val history = withContext(Dispatchers.IO) {
                dao.lastNDesc(Predictor.WINDOW).asReversed()
            }
            val pred = withContext(Dispatchers.Default) {
                Predictor.predict(history)
            }
            // Hiển thị list mới nhất lên đầu
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

        // Đếm T/X theo ngưỡng mới (≥ 11 = T)
        val totalT = h.count { Predictor.classify(it.sum) == 'T' }
        val totalX = h.size - totalT
        val tPct = totalT * 100.0 / h.size
        val xPct = totalX * 100.0 / h.size

        // Phân bố tổng
        val sumHist = IntArray(19)
        h.forEach { sumHist[it.sum]++ }
        val topSums = (3..18)
            .sortedByDescending { sumHist[it] }
            .take(6)
            .joinToString("  ") { "$it:${sumHist[it]}" }

        // Phân bố mặt xúc xắc
        val faces = Array(3) { IntArray(7) }
        h.forEach {
            faces[0][it.d1]++
            faces[1][it.d2]++
            faces[2][it.d3]++
        }
        fun facesLine(a: IntArray) =
            (1..6).joinToString(" ") { "$it:${a[it]}" }

        // Đếm đám đông
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
                // Lần cuối + dự đoán hiện tại
                append("Lần cuối: ${h.last().d1}-${h.last().d2}-${h.last().d3} = ")
                append("${h.last().sum} (${Predictor.classify(h.last().sum)})\n")
                append("Dự đoán tiếp: ${if (p.nextIsT) "T" else "X"} ")
                append("(T ${"%.1f%%".format(p.probT * 100)}")
                append(" / X ${"%.1f%%".format(p.probX * 100)})\n")
                append("Tổng khả năng nhất: ${p.bestSum}\n")
                append("Mẫu dùng để tính: ${p.sampleCount}/${Predictor.WINDOW}\n")

                // Ensemble Hedge
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

                // Trọng số chuyên gia
                append("\n--- Trọng số chuyên gia ---\n")
                p.expertWeights
                    .sortedByDescending { it.second }
                    .forEach { (name, w) ->
                        val padded = name.padEnd(9)
                        append("  $padded ${"%.1f%%".format(w * 100)}\n")
                    }

                // Trạng thái đặc biệt
                if (p.regimeChange) {
                    append("\n⚠️ REGIME CHANGE đang diễn ra\n")
                }
                if (p.adversarialMode) {
                    append("\n🔴 CHẾ ĐỘ ĐỐI KHÁNG đang BẬT\n")
                }
            }

            // Đám đông
            if (crowdTotal > 0) {
                append("\n--- Đám đông ---\n")
                append("Số lần có chọn: $crowdTotal\n")
                append("Nhà cái phá đám đông: $crowdWrong ")
                append("(${"%.0f%%".format(crowdWrong * 100.0 / crowdTotal)})\n")
            }

            // Phân bố
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
    // RECYCLERVIEW ADAPTER
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

            // Đánh dấu đúng/sai của dự đoán đã lưu trong dòng này
            val marks = if (r.hasPred) {
                val t = if (r.classCorrect) "✓" else "✗"
                val s = if (r.sumCorrect) "✓" else "✗"
                "  [T/X $t · Điểm $s]"
            } else ""

            // Đánh dấu đám đông nếu có
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
                        // Cập nhật lại đúng/sai khi sửa
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
    // DIFF CALLBACK
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
