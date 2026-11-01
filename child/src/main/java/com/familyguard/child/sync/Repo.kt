package com.familyguard.child.sync

import android.content.Context
import com.familyguard.child.Prefs
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

object Repo {
    val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()

    fun uid(): String? = FirebaseAuth.getInstance().currentUser?.uid

    fun device(id: String): DocumentReference = db.collection("devices").document(id)

    fun commandResult(ctx: Context, cmdId: String, status: String, message: String) {
        val id = Prefs(ctx).deviceId ?: return
        device(id).collection("commands").document(cmdId).update(
            mapOf(
                "status" to status,
                "result" to message,
                "doneAt" to FieldValue.serverTimestamp(),
            )
        )
    }
}
