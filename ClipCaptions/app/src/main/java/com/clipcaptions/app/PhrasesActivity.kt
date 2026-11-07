package com.clipcaptions.app

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.text.DateFormat
import java.util.Date

/** The phrases saved with ★ on the caption box: translation, words as heard, language and date. */
class PhrasesActivity : AppCompatActivity() {

    private lateinit var heading: TextView
    private lateinit var empty: TextView
    private lateinit var list: ListView
    private lateinit var share: Button
    private var phrases: List<Phrase> = emptyList()

    private fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(24f), dp(24f), dp(8f))
        }
        heading = TextView(this).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineMedium)
        }
        val note = TextView(this).apply {
            text = "Tap ☆ on the caption box to save what's being said. Tap a phrase to copy or delete it."
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setPadding(0, dp(6f), 0, dp(10f))
        }
        share = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Share all"
            setOnClickListener { shareAll() }
        }
        empty = TextView(this).apply {
            text = "No phrases yet. While captions are on, tap ☆ on the caption box to save the words on screen."
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            setPadding(0, dp(24f), 0, 0)
        }
        list = ListView(this).apply {
            dividerHeight = dp(1f)
            setOnItemClickListener { _, _, position, _ -> choose(phrases[position]) }
        }
        root.addView(heading)
        root.addView(note)
        root.addView(share, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(empty)
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        padForSystemBars(root, dp(24f), dp(24f))
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        phrases = PhraseBook.all(this)
        heading.text = if (phrases.isEmpty()) "My phrases" else "My phrases (${phrases.size})"
        empty.visibility = if (phrases.isEmpty()) View.VISIBLE else View.GONE
        share.visibility = if (phrases.isEmpty()) View.GONE else View.VISIBLE
        list.adapter = Adapter()
    }

    private fun choose(phrase: Phrase) {
        AlertDialog.Builder(this)
            .setTitle(phrase.translation ?: phrase.original)
            .setItems(arrayOf("Copy", "Delete")) { _, which ->
                if (which == 0) copy(PhraseBook.asText(listOf(phrase))) else confirmDelete(phrase)
            }
            .show()
    }

    private fun confirmDelete(phrase: Phrase) {
        AlertDialog.Builder(this)
            .setMessage("Delete this phrase?")
            .setPositiveButton("Delete") { _, _ ->
                PhraseBook.remove(this, phrase.savedAt)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Phrase", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareAll() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "My Clip Captions phrases")
            putExtra(Intent.EXTRA_TEXT, PhraseBook.asText(phrases))
        }
        startActivity(Intent.createChooser(send, "Share your phrases"))
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = phrases.size
        override fun getItem(position: Int) = phrases[position]
        override fun getItemId(position: Int) = phrases[position].savedAt

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val p = phrases[position]
            val row = LinearLayout(this@PhrasesActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(12f), 0, dp(12f))
            }
            val translated = !p.translation.isNullOrBlank() && p.translation != p.original
            row.addView(TextView(this@PhrasesActivity).apply {
                text = if (translated) p.translation else p.original
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            })
            if (translated) {
                row.addView(TextView(this@PhrasesActivity).apply {
                    text = p.original
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                })
            }
            row.addView(TextView(this@PhrasesActivity).apply {
                val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(p.savedAt))
                text = "${Languages.nameFor(p.language)} · $date"
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall)
                setPadding(0, dp(4f), 0, 0)
            })
            return row
        }
    }
}
