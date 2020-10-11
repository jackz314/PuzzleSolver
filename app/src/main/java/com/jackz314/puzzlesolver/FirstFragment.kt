package com.jackz314.puzzlesolver

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.jackz314.puzzlesolver.MainActivity.Companion.durationPref
import com.jackz314.puzzlesolver.MainActivity.Companion.expIdlePref
import com.jackz314.puzzlesolver.MainActivity.Companion.useRootPref
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * A simple [Fragment] subclass as the default destination in the navigation.
 */
class FirstFragment : Fragment() {

    override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_first, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        GlobalScope.launch(Dispatchers.IO) {
            val sharedPref = requireActivity().getSharedPreferences(
                getString(R.string.preference_file_key), Context.MODE_PRIVATE)
            view.findViewById<EditText>(R.id.durationEdit).setText(
                sharedPref.getInt(durationPref, -1).toString().let {if (it == "-1") "" else it})
            view.findViewById<CheckBox>(R.id.expIdleCheck).isChecked =
                sharedPref.getBoolean(expIdlePref, false)
            view.findViewById<CheckBox>(R.id.useRootCheck).isChecked =
                sharedPref.getBoolean(useRootPref, false)
        }
        view.findViewById<Button>(R.id.button_first).setOnClickListener {
            findNavController().navigate(R.id.action_FirstFragment_to_SecondFragment)
        }
    }
}