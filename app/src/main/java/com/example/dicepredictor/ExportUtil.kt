package com.example.dicepredictor

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.dicepredictor.db.DiceResult
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ExportUtil {

    /** Thư mục chứa file xuất: /Android/data/<pkg>/files/exports/ */
    fun getExportDir(ctx: Context): File {
        val dir = File(ctx.getExternalFilesDir(null), "exports")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    // ============================================================
    // CSV — dễ mở bằng Excel / Google Sheets
    // ============================================================
    fun exportCsv(ctx: Context, data: List<DiceResult>): File {
        val file = File(getExportDir(ctx), "dice_${timestamp()}.csv")
        BufferedWriter(file.writer()).use { w ->
            // Header
            w.write(
                "id,timestamp_iso,d1,d2,d3,sum,class," +
                "has_pred,pred_sum,pred_was_t,pred_prob_t," +
                "sum_correct,class_correct,crowd_choice\n"
            )
            val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            data.forEach { r ->
                w.write(
                    "${r.id}," +
                    "${iso.format(Date(r.timestamp))}," +
                    "${r.d1},${r.d2},${r.d3},${r.sum}," +
                    "${Predictor.classify(r.sum)}," +
                    "${if (r.hasPred) 1 else 0}," +
                    "${r.predSum}," +
                    "${if (r.predWasT) 1 else 0}," +
                    "${"%.4f".format(r.predProbT)}," +
                    "${if (r.sumCorrect) 1 else 0}," +
                    "${if (r.classCorrect) 1 else 0}," +
                    "${r.crowdChoice}\n"
                )
            }
        }
        return file
    }

    // ============================================================
    // JSON — dễ parse bằng Python / R / pandas
    // ============================================================
    fun exportJson(ctx: Context, data: List<DiceResult>): File {
        val file = File(getExportDir(ctx), "dice_${timestamp()}.json")
        BufferedWriter(file.writer()).use { w ->
            w.write("[\n")
            data.forEachIndexed { i, r ->
                w.write("  {")
                w.write("\"id\":${r.id},")
                w.write("\"ts\":${r.timestamp},")
                w.write("\"d1\":${r.d1},\"d2\":${r.d2},\"d3\":${r.d3},")
                w.write("\"sum\":${r.sum},")
                w.write("\"cls\":\"${Predictor.classify(r.sum)}\",")
                w.write("\"hasPred\":${r.hasPred},")
                w.write("\"predSum\":${r.predSum},")
                w.write("\"predWasT\":${r.predWasT},")
                w.write("\"predProbT\":${"%.4f".format(r.predProbT)},")
                w.write("\"sumCorrect\":${r.sumCorrect},")
                w.write("\"classCorrect\":${r.classCorrect},")
                w.write("\"crowd\":\"${r.crowdChoice}\"")
                w.write("}")
                if (i < data.size - 1) w.write(",")
                w.write("\n")
            }
            w.write("]\n")
        }
        return file
    }

    // ============================================================
    // Share file qua email / Drive / Zalo / v.v.
    // ============================================================
    fun shareFile(ctx: Context, file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            ctx,
            "${ctx.packageName}.fileprovider",
            file
        )
        val mime = when (file.extension.lowercase()) {
            "csv" -> "text/csv"
            "json" -> "application/json"
            else -> "*/*"
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Dice Predictor export — ${file.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(intent, "Chia sẻ file export"))
    }
}
