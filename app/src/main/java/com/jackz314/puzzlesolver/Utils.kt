package com.jackz314.puzzlesolver

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.ServiceInfo
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager

private const val TAG = "Utils"

class Utils {
    companion object{
        fun isAccessibilityServiceEnabled(context: Context, service: Class<out AccessibilityService?>): Boolean {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            Log.d(TAG, "isAccessibilityServiceEnabled: enabled services: $enabledServices")
//            if(enabledServices.isEmpty()) return isAccessibilityServiceEnabledAlternative(context, service)
            return if(enabledServices
                .map { it.resolveInfo.serviceInfo }
                .any { it.packageName == context.packageName && it.name == service.name }) true
            else isAccessibilityServiceEnabledAlternative(context, service)
        }

        private fun isAccessibilityServiceEnabledAlternative(context: Context, service: Class<out AccessibilityService?>): Boolean {
            val prefString =
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            Log.d(TAG, "isAccessibilityServiceEnabledAlternative: enabled services: $prefString")
            return prefString?.contains("${context.packageName}/.${service.simpleName}") ?: false
        }
    }
}