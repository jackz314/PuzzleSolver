package com.jackz314.puzzlesolver

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.isDigitsOnly
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.DataOutputStream


private const val TAG = "MainActivity"

class MainActivity : AppCompatActivity() {

    companion object {
        val durationPref = "DURATION"
        val delayPref = "DELAY"
        val expIdlePref = "EXPONENTIAL_IDLE"
        val useRootPref = "USE_ROOT"
    }

    private val overlayRC = 1023
    private val accessRC = 2023
    private lateinit var fab: FloatingActionButton

    private var mService: SolveService? = null

    /** Defines callbacks for service binding, passed to bindService()  */
    private val connection = object : ServiceConnection {

        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance
            val binder = service as SolveService.SolveServiceBinder
            mService = binder.getService()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            mService = null
            fab.setImageResource(R.drawable.ic_play_24)
        }
    }

    private fun start(){
        if(!SolveService.running) {
            /*if(GestureService.getInstance() == null){
                Toast.makeText(this, "Accessibility Service not available, try setting it manually", Toast.LENGTH_SHORT).show()
                openAccessibilitySettings()
            }*/
            val intent = Intent(applicationContext, SolveService::class.java)
            var swipeDuration =
                findViewById<EditText>(R.id.durationEdit).text.toString().run {if (length > 0 && isDigitsOnly()) toInt() else 10}
            if (swipeDuration < 1) {
                swipeDuration = 1
                findViewById<EditText>(R.id.durationEdit).setText("1")
            }
            var delay =
                findViewById<EditText>(R.id.delayEdit).text.toString().run {if (length > 0 && isDigitsOnly()) toInt() else 10}
            if (delay < 0) {
                delay = 0
                findViewById<EditText>(R.id.delayEdit).setText("0")
            }
            val isExpIdle = findViewById<CheckBox>(R.id.expIdleCheck).isChecked
            GlobalScope.launch(Dispatchers.IO) {
                val sharedPref = getSharedPreferences(
                    getString(R.string.preference_file_key), Context.MODE_PRIVATE)
                with(sharedPref.edit()) {
                    putInt(durationPref, swipeDuration)
                    putInt(delayPref, delay)
                    putBoolean(expIdlePref, isExpIdle)
                    putBoolean(useRootPref, findViewById<CheckBox>(R.id.useRootCheck).isChecked)
                    apply()
                }
            }
            intent.putExtra(durationPref, swipeDuration)
            intent.putExtra(delayPref, delay)
            intent.putExtra(expIdlePref, isExpIdle)
            startService(intent)
            Intent(this, SolveService::class.java).apply {
                bindService(this, connection, Context.BIND_ABOVE_CLIENT)
            }
            fab.isEnabled = true
            fab.setImageResource(R.drawable.ic_stop_24)
        }
    }

    private fun stop(): Boolean{
        return if(SolveService.running) {
            mService?.exit()
//            stopService(Intent(applicationContext, SolveService::class.java))
            try {
                unbindService(connection)
            } catch (e: Exception) {
                Log.d(TAG, "stop: unbind failed")
            }
            true
        }else false
    }

    private fun runRootCmds(cmds: Array<String>): Int{
        val suProc = ProcessBuilder("su").start()

        if(suProc.outputStream != null){
            val suOut = DataOutputStream(suProc.outputStream)
            for (cmd in cmds) suOut.writeBytes(cmd+"\n")
            suOut.writeBytes("exit\n")
            suOut.flush()
            suOut.close()
        }else{ // OutputStream null, unknown problem
            return Integer.MAX_VALUE
        }

        val waitSuProc = ProcessWithTimeout(suProc)
        return waitSuProc.waitForProcess(60000).also { Log.d(TAG, "runRootCmds: return code: $it") }
    }

    private fun promptForAccessibilityService(){
        AlertDialog.Builder(this)
            .setTitle("Accessibility Service Permission Request")
            .setMessage("We need accessibility services permission to interact with puzzles.\n\nOn the next screen, find Puzzle Solver and turn on the service.")
            .setPositiveButton("OK"){ _, _ -> openAccessibilitySettings()}.show()
    }

    private fun tryStart(){
        //check accessibility service status
        fab.isEnabled = false
        if(Utils.isAccessibilityServiceEnabled(this, GestureService::class.java)){
            if(Settings.canDrawOverlays(applicationContext)){//check if have overlay permission
                start()
            }else{//don't have permission, ask for it
                Toast.makeText(
                    this,
                    "Please grant overlay permission for the puzzle solver UI",
                    Toast.LENGTH_SHORT
                ).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, overlayRC)
            }
        }else{//ask for permission
            if (findViewById<CheckBox>(R.id.useRootCheck).isChecked) {//try root process
                //enable accessibility services with root cmd
                GlobalScope.launch {
                    runRootCmds(arrayOf(
                        "settings delete secure ${Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES}",
                        "settings put secure ${Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES} $packageName/.${GestureService::class.java.simpleName}",
                        "settings put secure ${Settings.Secure.ACCESSIBILITY_ENABLED} 1"))
                    if (Utils.isAccessibilityServiceEnabled(applicationContext, GestureService::class.java)) runOnUiThread {
                        Toast.makeText(applicationContext, "Enabled accessibility service with root!", Toast.LENGTH_SHORT).show()
                        tryStart()
                    }
                    else runOnUiThread {promptForAccessibilityService()}
                }
            }else{
                promptForAccessibilityService()
            }
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

        //below is undocumented, discovered from Android SettingsActivity source code, see https://stackoverflow.com/a/63214655/8170714
        val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
        val EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"

        val bundle = Bundle()
        val showArgs: String =
            packageName.toString() + "/" + GestureService::class.java.name
        bundle.putString(EXTRA_FRAGMENT_ARG_KEY, showArgs)
        intent.putExtra(EXTRA_FRAGMENT_ARG_KEY, showArgs)
        intent.putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, bundle)
        startActivityForResult(intent, accessRC)
    }

    override fun onResume() {
        super.onResume()
        if (SolveService.running) {
            fab.setImageResource(R.drawable.ic_stop_24)
        } else {
            fab.setImageResource(R.drawable.ic_play_24)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))

        fab = findViewById(R.id.fab)
        fab.setOnClickListener {
            if (!SolveService.running) {
//                Snackbar.make(view, "Starting", Snackbar.LENGTH_SHORT).show()
                Toast.makeText(applicationContext, "Starting", Toast.LENGTH_SHORT).show()
                tryStart()
            } else {
//                Snackbar.make(view, "Stoping", Snackbar.LENGTH_SHORT).show()
                Toast.makeText(applicationContext, "Stopping", Toast.LENGTH_SHORT).show()
                stop()
                fab.setImageResource(R.drawable.ic_play_24)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        return when (item.itemId) {
            R.id.action_settings -> true
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if(requestCode == overlayRC){
            if (Settings.canDrawOverlays(this)) {
                // permission granted...
                Toast.makeText(this, "Thank you!", Toast.LENGTH_SHORT).show()
                start()
            }else{
                // permission not granted...
                fab.isEnabled = true
                Toast.makeText(this, "Overlay permission not granted :(", Toast.LENGTH_SHORT).show()
            }
        }else if(requestCode == accessRC){
//            Log.d(TAG, "onActivityResult: Result of opening accessibility settings: $resultCode, $data")
            if(Utils.isAccessibilityServiceEnabled(this, GestureService::class.java)){
                Toast.makeText(this, "Thank you!", Toast.LENGTH_SHORT).show()
                tryStart()
            }
            else {
                fab.isEnabled = true
                Toast.makeText(this, "Accessibility Service permission not granted :(", Toast.LENGTH_SHORT).show()
            }
        }
    }
}