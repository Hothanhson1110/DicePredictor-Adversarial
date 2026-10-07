package com.example.dicepredictor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
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

class DashboardActivity : AppCompatActivity() {

    private lateinit var b: ActivityDashboardBinding
    private lateinit var dao: DiceDao
    private val adapter = Adapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(b.root)

        dao = AppDatabase.get(this).diceDao()

        b.rvResults.layoutManager = LinearLayoutManager(this)
        b.rvResults.adapter = adapter

        b.btnBack.setOnClickListener { finish() }
        b.btnRefresh.setOnClickListener { reload() }

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
                            AppDatabase.vacuum(AppDatabase.get(this@DashboardActivity))
                        }
                        reload()
                    }
                }
                .setNegativeButton("Huỷ", null)
                .show()
        }

        reload()
    }

    private fun reload() {
        lifecycleScope.launch {
            val history = withContext(Dispatchers.IO) {
                dao.lastNDesc(Predictor.WINDOW).asReversed()
            }
            val pred = withContext(Dispatchers.Default) { Predictor.predict(history) }
            adapter.submitList(history.asReversed())
            renderStats(history, pred)
        }
    }

    private fun renderStats(h: List<DiceResult>, p: Predictor.Prediction?) {
        if (h.isEmpty()) { b.tvStats.text = "Database trống."; return }

        val totalT = h.count { Predictor.classify(it.sum) == 'T' }
        val totalX = h.size - totalT
        val tPct = totalT * 100.0 / h.size
        val xPct = totalX * 100.0 / h.size

        val sumHist = IntArray(19)
        h.forEach { sumHist[it.sum]++ }
        val topSums = (3..18).sortedByDescending { sumHist[it] }
            .take(6).joinToString("  ") { "$it:${sumHist[it]}" }

        val faces = Array(3) { IntArray(7) }
        h.forEach { faces[0][it.d1]++; faces[1][it.d2]++; faces[2][it.d3]++ }
        fun line(a: IntArray) = (1..6).joinToString(" ") { "$it:${a[it]}" }

        // Đếm đám đông
        val crowdTotal = h.count { it.crowdChoice != '?' }
        val crowdWrong = h.count { r ->
            if (r.crowdChoice == '?') false
            else (r.crowdChoice == 'T') != (r.sum >= 10)
        }

        b.tvStats.text = buildString {
            append("Tổng mẫu: ${h.size} / ${Predictor.WINDOW}\n")
            append("T (10-18): $totalT   (${"%.1f%%".format(tPct)})\n")
            append("X (3-9)  : $totalX   (${"%.1f%%".format(xPct)})\n\n")
            if (p != null) {
                append("Lần cuối: ${h.last().d1}-${h.last().d2}-${h.last().d3} = ")
                append("${h.last().sum} (${Predictor.classify(h.last().sum)})\n")
                append("Dự đoán tiếp: ${if (p.nextIsT) "T" else "X"} ")
                append("(T ${"%.1f%%".format(p.probT * 100)} / X ${"%.1f%%".format(p.probX * 100)})\n")
                append("Tổng khả năng nhất: ${p.bestSum}\n")
                append("Mẫu dùng để tính: ${p.sampleCount}/${Predictor.WINDOW}\n")

                if (p.feedbackSamples > 0) {
                    append("\n--- Tự học ---\n")
                    append("Accuracy T/X (${p.feedbackSamples} gần nhất): ")
                    append("${"%.1f%%".format(p.accClassRecent * 100)}\n")
                    append("Accuracy Điểm: ")
                    append("${"%.1f%%".format(p.accSumRecent * 100)}\n")
                    append("Streak T/X: ${p.streakClass}   Streak Điểm: ${p.streakSum}\n")
                    append("Bias T đang áp: ${"%+.1f%%".format(p.biasT * 100)}\n")
                    append("Adaptive k: ${"%.2f".format(p.adaptiveK)}\n")
                    append("Markov b2 pT: ${"%.1f%%".format(p.markov2ProbT * 100)}\n")
                    append("Raw pT (trước adjust): ${"%.1f%%".format(p.rawPT * 100)}\n")
                    if (p.adversarialMode) {
                        append("🔴 Chế độ đối kháng đang BẬT\n")
                    }
                }

                if (crowdTotal > 0) {
                    append("\n--- Đám đông ---\n")
                    append("Số lần có chọn: $crowdTotal\n")
                    append("Nhà cái phá đám đông: $crowdWrong ")
                    append("(${"%.0f%%".format(crowdWrong * 100.0 / crowdTotal)})\n")
                    append("Contrarian adj đang áp: ")
                    append("${"%+.1f%%".format(p.contrarianAdj * 100)}\n")
                }
            }
            append("\nPhân bố tổng (top 6):\n  $topSums\n")
            append("\nMặt X1: ${line(faces[0])}\n")
            append("Mặt X2: ${line(faces[1])}\n")
            append("Mặt X3: ${line(faces[2])}\n")
        }
    }

    private inner class Adapter : ListAdapter<DiceResult, VH>(DIFF) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ib = ItemResultBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(ib)
        }
        override fun onBindViewHolder(holder: VH, position: Int) =
            holder.bind(getItem(position), position)
    }

    private inner class VH(val ib: ItemResultBinding) : RecyclerView.ViewHolder(ib.root) {
        fun bind(r: DiceResult, pos: Int) {
            ib.tvIdx.text = "#${pos + 1}"
            val marks = if (r.hasPred) {
                val t = if (r.classCorrect) "✓" else "✗"
                val s = if (r.sumCorrect) "✓" else "✗"
                "  [T/X $t · Điểm $s]"
            } else ""
            val crowdMark = if (r.crowdChoice != '?') "  👥${r.crowdChoice}" else ""
            ib.tvDice.text = "${r.d1} - ${r.d2} - ${r.d3}   =   ${r.sum}$marks$crowdMark"
            val cls = Predictor.classify(r.sum)
            ib.tvCls.text = cls.toString()
            ib.tvCls.setTextColor(if (cls == 'T') 0xFF1E8E3E.toInt() else 0xFFD93025.toInt())

            ib.btnEdit.setOnClickListener { showEditDialog(r) }
            ib.btnDelete.setOnClickListener {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { dao.delete(r) }
                    reload()
                }
            }
        }
    }

    private fun showEditDialog(r: DiceResult) {
        val v = DialogEditResultBinding.inflate(layoutInflater)
        listOf(v.np1 to r.d1, v.np2 to r.d2, v.np3 to r.d3).forEach { (np, v0) ->
            np.minValue = 1; np.maxValue = 6; np.value = v0
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Sửa kết quả")
            .setView(v.root)
            .setPositiveButton("Lưu") { _, _ ->
                val d1 = v.np1.value; val d2 = v.np2.value; val d3 = v.np3.value
                val newSum = d1 + d2 + d3
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        dao.update(
                            r.copy(
                                d1 = d1, d2 = d2, d3 = d3, sum = newSum,
                                sumCorrect = if (r.hasPred) r.predSum == newSum else false,
                                classCorrect = if (r.hasPred)
                                    r.predWasT == (newSum >= 10) else false
                            )
                        )
                    }
                    reload()
                }
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<DiceResult>() {
            override fun areItemsTheSame(a: DiceResult, b: DiceResult) = a.id == b.id
            override fun areContentsTheSame(a: DiceResult, b: DiceResult) = a == b
        }
    }
}
