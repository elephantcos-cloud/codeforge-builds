package com.codeforge.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.codeforge.api.BuildEngine
import com.codeforge.databinding.ActivityMainBinding
import com.codeforge.viewmodel.BuildViewModel

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val vm: BuildViewModel by viewModels()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setupLangSpinner()
        setupTemplates()
        setupBuildButton()
        observeState()
    }

    private fun setupLangSpinner() {
        b.spinnerLang.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_item,
            listOf("HTML", "React", "JavaScript")
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    }

    private fun setupTemplates() {
        b.btnHtml.setOnClickListener {
            b.etCode.setText(TPL_HTML); b.spinnerLang.setSelection(0)
        }
        b.btnReact.setOnClickListener {
            b.etCode.setText(TPL_REACT); b.spinnerLang.setSelection(1)
        }
        b.btnJs.setOnClickListener {
            b.etCode.setText(TPL_JS); b.spinnerLang.setSelection(2)
        }
    }

    private fun setupBuildButton() {
        b.btnBuild.setOnClickListener {
            val code = b.etCode.text.toString()
            val name = b.etAppName.text.toString().trim().ifBlank { "My App" }
            val pkg  = b.etPkg.text.toString().trim()
                .replace(" ", "").lowercase()
                .ifBlank { "com.myapp.${System.currentTimeMillis() % 10000}" }

            if (code.isBlank()) {
                Toast.makeText(this, "Write some code first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val lang = when (b.spinnerLang.selectedItemPosition) {
                1    -> BuildEngine.Lang.REACT
                2    -> BuildEngine.Lang.JAVASCRIPT
                else -> BuildEngine.Lang.HTML
            }

            vm.build(name, pkg, code, lang)
        }

        b.btnCancel.setOnClickListener { vm.reset() }
    }

    private fun observeState() {
        vm.state.observe(this) { state ->
            when (state) {
                is BuildViewModel.State.Idle -> {
                    b.cardProgress.visibility = View.GONE
                    b.cardResult.visibility   = View.GONE
                    b.btnBuild.isEnabled = true
                    b.btnCancel.visibility = View.GONE
                }
                is BuildViewModel.State.Running -> {
                    b.cardProgress.visibility = View.VISIBLE
                    b.cardResult.visibility   = View.GONE
                    b.btnBuild.isEnabled = false
                    b.btnCancel.visibility = View.VISIBLE
                    b.progressBar.progress = state.progress
                    b.tvProgress.text = state.message
                }
                is BuildViewModel.State.Done -> {
                    b.cardProgress.visibility = View.GONE
                    b.cardResult.visibility   = View.VISIBLE
                    b.btnBuild.isEnabled = true
                    b.btnCancel.visibility = View.GONE
                    b.tvResult.text = "APK build complete!"
                    b.btnDownload.setOnClickListener {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.url)))
                    }
                }
                is BuildViewModel.State.Failed -> {
                    b.cardProgress.visibility = View.GONE
                    b.cardResult.visibility   = View.VISIBLE
                    b.btnBuild.isEnabled = true
                    b.btnCancel.visibility = View.GONE
                    b.tvResult.text = state.reason
                    b.tvResult.setTextColor(getColor(com.codeforge.R.color.error))
                    b.btnDownload.visibility = View.GONE
                }
            }
        }
    }

    companion object {
        val TPL_HTML = """<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body { font-family: sans-serif; background: #0f0f23;
           color: #ccc; min-height: 100vh;
           display: flex; align-items: center; justify-content: center; }
    .card { background: #1e1e3f; padding: 32px; border-radius: 16px;
            text-align: center; max-width: 320px; width: 90%; }
    h1 { color: #ff6d00; margin-bottom: 12px; }
    button { margin-top: 20px; padding: 12px 28px; background: #ff6d00;
             color: #fff; border: none; border-radius: 8px;
             font-size: 16px; cursor: pointer; }
  </style>
</head>
<body>
  <div class="card">
    <h1>Hello World!</h1>
    <p>This app was built with CodeForge.</p>
    <button onclick="alert('It works!')">Tap me</button>
  </div>
</body>
</html>""".trimIndent()

        val TPL_REACT = """const { useState } = React;

function App() {
  const [count, setCount] = useState(0);
  const style = {
    body: { fontFamily:'sans-serif', background:'#0f0f23',
            minHeight:'100vh', display:'flex',
            alignItems:'center', justifyContent:'center', margin:0 },
    card: { background:'#1e1e3f', padding:32, borderRadius:16, textAlign:'center' },
    h1:   { color:'#ff6d00', marginBottom:12 },
    btn:  { marginTop:20, padding:'12px 28px', background:'#ff6d00',
            color:'#fff', border:'none', borderRadius:8,
            fontSize:16, cursor:'pointer' }
  };
  return (
    <div style={style.body}>
      <div style={style.card}>
        <h1 style={style.h1}>React App</h1>
        <p>Count: {count}</p>
        <button style={style.btn} onClick={() => setCount(c => c+1)}>
          Tap me
        </button>
      </div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<App/>);""".trimIndent()

        val TPL_JS = """document.body.style.cssText =
  'margin:0;background:#0f0f23;display:flex;align-items:center;' +
  'justify-content:center;min-height:100vh;font-family:sans-serif;color:#ccc';

const card = document.createElement('div');
card.style.cssText =
  'background:#1e1e3f;padding:32px;border-radius:16px;text-align:center';

const h1 = document.createElement('h1');
h1.textContent = 'JS App';
h1.style.color = '#ff6d00';

const btn = document.createElement('button');
let n = 0;
btn.textContent = 'Tap count: 0';
btn.style.cssText =
  'margin-top:20px;padding:12px 28px;background:#ff6d00;' +
  'color:#fff;border:none;border-radius:8px;font-size:16px;cursor:pointer';
btn.onclick = () => { n++; btn.textContent = 'Tap count: ' + n; };

card.append(h1, btn);
document.body.appendChild(card);""".trimIndent()
    }
}
