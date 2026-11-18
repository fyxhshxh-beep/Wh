package com.friendcall.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import org.jitsi.meet.sdk.JitsiMeet
import org.jitsi.meet.sdk.JitsiMeetActivity
import org.jitsi.meet.sdk.JitsiMeetConferenceOptions
import java.net.URL

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FriendCallV2() }
    }
}

private data class Friend(val uid: String, val email: String)
private data class ChatMessage(val sender: String, val body: String)

@Composable
private fun FriendCallV2() {
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    var user by remember { mutableStateOf(auth.currentUser) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var friendEmail by remember { mutableStateOf("") }
    var roomCode by remember { mutableStateOf("") }
    var chatText by remember { mutableStateOf("") }
    var selectedFriend by remember { mutableStateOf<Friend?>(null) }
    var friends by remember { mutableStateOf<List<Friend>>(emptyList()) }
    var requests by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var tab by remember { mutableStateOf("Friends") }
    var status by remember { mutableStateOf("Welcome to FriendCall V2.") }
    var busy by remember { mutableStateOf(false) }
    val context = LocalContext.current

    fun saveProfile(uid: String, address: String) {
        db.collection("users").document(uid).set(
            mapOf("uid" to uid, "email" to address.lowercase(), "createdAt" to System.currentTimeMillis()),
            com.google.firebase.firestore.SetOptions.merge()
        )
    }

    DisposableEffect(user?.uid) {
        val uid = user?.uid
        if (uid == null) {
            onDispose { }
        } else {
            saveProfile(uid, user?.email.orEmpty())
            val friendListener: ListenerRegistration = db.collection("friendships")
                .whereArrayContains("members", uid)
                .addSnapshotListener { snap, _ ->
                    friends = snap?.documents?.mapNotNull { doc ->
                        val ids = doc.get("members") as? List<*> ?: emptyList<Any>()
                        val emails = doc.get("emails") as? List<*> ?: emptyList<Any>()
                        val index = ids.indexOfFirst { it == uid }
                        val otherUid = ids.getOrNull(if (index == 0) 1 else 0) as? String
                        val otherEmail = emails.getOrNull(if (index == 0) 1 else 0) as? String
                        if (otherUid != null && otherEmail != null) Friend(otherUid, otherEmail) else null
                    } ?: emptyList()
                }
            val requestListener = db.collection("friendRequests")
                .whereEqualTo("toUid", uid)
                .whereEqualTo("status", "pending")
                .addSnapshotListener { snap, _ ->
                    requests = snap?.documents?.mapNotNull { d ->
                        val id = d.id
                        val fromEmail = d.getString("fromEmail") ?: return@mapNotNull null
                        id to fromEmail
                    } ?: emptyList()
                }
            onDispose {
                friendListener.remove()
                requestListener.remove()
            }
        }
    }

    DisposableEffect(selectedFriend?.uid, user?.uid) {
        val me = user?.uid
        val friend = selectedFriend
        if (me == null || friend == null) {
            messages = emptyList()
            onDispose { }
        } else {
            val sortedMembers = listOf(me, friend.uid).sorted()
            val chatId = sortedMembers.joinToString("_")
            db.collection("chats").document(chatId).set(
                mapOf("members" to sortedMembers), com.google.firebase.firestore.SetOptions.merge()
            )
            val listener = db.collection("chats").document(chatId).collection("messages")
                .orderBy("createdAt")
                .addSnapshotListener { snap, _ ->
                    messages = snap?.documents?.map {
                        ChatMessage(it.getString("senderEmail") ?: "Friend", it.getString("text") ?: "")
                    } ?: emptyList()
                }
            onDispose { listener.remove() }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFF9A8CFF),
        secondary = androidx.compose.ui.graphics.Color(0xFF61D9C5),
        background = androidx.compose.ui.graphics.Color(0xFF101322),
        surface = androidx.compose.ui.graphics.Color(0xFF1A2033)
    )) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("FriendCall", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("Version 2 · Friends, chat and calls", color = MaterialTheme.colorScheme.secondary)

                if (user == null) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Welcome", style = MaterialTheme.typography.titleLarge)
                            OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(password, { password = it }, label = { Text("Password (6+ characters)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                                if (email.isBlank() || password.length < 6) status = "Enter a valid email and a password of at least 6 characters."
                                else {
                                    busy = true
                                    auth.signInWithEmailAndPassword(email.trim(), password).addOnCompleteListener { task ->
                                        busy = false
                                        if (task.isSuccessful) {
                                            user = auth.currentUser
                                            saveProfile(auth.currentUser!!.uid, auth.currentUser!!.email.orEmpty())
                                            status = "Signed in."
                                        } else status = task.exception?.localizedMessage ?: "Sign-in failed."
                                    }
                                }
                            }) { Text("Sign in") }
                            OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                                if (email.isBlank() || password.length < 6) status = "Enter a valid email and a password of at least 6 characters."
                                else {
                                    busy = true
                                    auth.createUserWithEmailAndPassword(email.trim(), password).addOnCompleteListener { task ->
                                        busy = false
                                        if (task.isSuccessful) {
                                            user = auth.currentUser
                                            saveProfile(auth.currentUser!!.uid, auth.currentUser!!.email.orEmpty())
                                            status = "Account created."
                                        } else status = task.exception?.localizedMessage ?: "Account creation failed."
                                    }
                                }
                            }) { Text("Create account") }
                        }
                    }
                } else {
                    Text("Signed in as ${user?.email}", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Friends", "Chats", "Call").forEach { name ->
                            FilterChip(selected = tab == name, onClick = { tab = name }, label = { Text(name) })
                        }
                    }
                    when (tab) {
                        "Friends" -> {
                            OutlinedTextField(friendEmail, { friendEmail = it }, label = { Text("Friend's email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Button(onClick = {
                                val targetEmail = friendEmail.trim().lowercase()
                                if (targetEmail.isBlank() || targetEmail == user?.email?.lowercase()) status = "Enter a different friend's email."
                                else db.collection("users").whereEqualTo("email", targetEmail).limit(1).get()
                                    .addOnSuccessListener { result ->
                                        val target = result.documents.firstOrNull()
                                        if (target == null) status = "No FriendCall account found for that email yet."
                                        else {
                                            val targetUid = target.id
                                            val requestId = "${user!!.uid}_$targetUid"
                                            db.collection("friendRequests").document(requestId).set(mapOf(
                                                "fromUid" to user!!.uid, "fromEmail" to user!!.email,
                                                "toUid" to targetUid, "toEmail" to targetEmail,
                                                "status" to "pending", "createdAt" to System.currentTimeMillis()
                                            )).addOnSuccessListener { status = "Friend request sent." }
                                        }
                                    }.addOnFailureListener { status = it.localizedMessage ?: "Could not search users." }
                            }, modifier = Modifier.fillMaxWidth()) { Text("Send friend request") }
                            if (requests.isNotEmpty()) Text("Friend requests", style = MaterialTheme.typography.titleMedium)
                            requests.forEach { (requestId, fromEmail) ->
                                Card(Modifier.fillMaxWidth()) {
                                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(fromEmail, Modifier.weight(1f))
                                        TextButton(onClick = {
                                            db.collection("friendRequests").document(requestId).get().addOnSuccessListener { req ->
                                                val fromUid = req.getString("fromUid") ?: return@addOnSuccessListener
                                                val fromAddress = req.getString("fromEmail") ?: "Friend"
                                                val me = user ?: return@addOnSuccessListener
                                                val ids = listOf(me.uid, fromUid).sorted()
                                                val emails = ids.map { id -> if (id == me.uid) me.email.orEmpty() else fromAddress }
                                                val friendshipId = ids.joinToString("_")
                                                db.collection("friendships").document(friendshipId).set(mapOf("members" to ids, "emails" to emails))
                                                db.collection("friendRequests").document(requestId).update("status", "accepted")
                                                status = "Friend added."
                                            }
                                        }) { Text("Accept") }
                                    }
                                }
                            }
                            Text("Your friends", style = MaterialTheme.typography.titleMedium)
                            if (friends.isEmpty()) Text("No friends yet. Send a request using their account email.")
                            friends.forEach { friend ->
                                Card(Modifier.fillMaxWidth()) {
                                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(friend.email, fontWeight = FontWeight.SemiBold)
                                            Text("Friend", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
                                        }
                                        TextButton(onClick = { selectedFriend = friend; tab = "Chats" }) { Text("Message") }
                                    }
                                }
                            }
                        }
                        "Chats" -> {
                            if (selectedFriend == null) {
                                Text("Choose a friend from the Friends tab to open a chat.")
                            } else {
                                Text("Chat with ${selectedFriend!!.email}", style = MaterialTheme.typography.titleMedium)
                                LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth().heightIn(max = 340.dp)) {
                                    items(messages) { msg ->
                                        Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                            Column(Modifier.padding(10.dp)) {
                                                Text(msg.sender, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                                Text(msg.body)
                                            }
                                        }
                                    }
                                }
                                OutlinedTextField(chatText, { chatText = it }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth())
                                Button(onClick = {
                                    val body = chatText.trim()
                                    val friend = selectedFriend
                                    val me = user
                                    if (body.isNotEmpty() && friend != null && me != null) {
                                        val chatId = listOf(me.uid, friend.uid).sorted().joinToString("_")
                                        db.collection("chats").document(chatId).set(
                                            mapOf("members" to listOf(me.uid, friend.uid).sorted()),
                                            com.google.firebase.firestore.SetOptions.merge()
                                        ).continueWithTask {
                                            db.collection("chats").document(chatId).collection("messages").add(mapOf(
                                                "senderUid" to me.uid, "senderEmail" to me.email,
                                                "text" to body, "createdAt" to System.currentTimeMillis()
                                            ))
                                        }.addOnSuccessListener { chatText = "" }
                                            .addOnFailureListener { status = it.localizedMessage ?: "Message failed." }
                                    }
                                }, modifier = Modifier.fillMaxWidth()) { Text("Send message") }
                            }
                        }
                        else -> {
                            Text("Start a private or group call", style = MaterialTheme.typography.titleMedium)
                            OutlinedTextField(roomCode, { roomCode = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(32) }, label = { Text("Room code") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Button(onClick = {
                                roomCode = java.util.UUID.randomUUID().toString().replace("-", "").take(10).uppercase()
                                status = "New room code created. Share it with friends."
                            }, modifier = Modifier.fillMaxWidth()) { Text("Generate room code") }
                            Button(onClick = {
                                if (roomCode.isBlank()) status = "Create or enter a room code first."
                                else {
                                    try {
                                        val server = URL("https://meet.jit.si")
                                        val defaults = JitsiMeetConferenceOptions.Builder()
                                            .setServerURL(server)
                                            .setWelcomePageEnabled(false)
                                            .setFeatureFlag("welcomepage.enabled", false)
                                            .build()
                                        JitsiMeet.setDefaultConferenceOptions(defaults)
                                        val options = JitsiMeetConferenceOptions.Builder()
                                            .setRoom("FriendCall-$roomCode")
                                            .setAudioOnly(false)
                                            .setAudioMuted(false)
                                            .setVideoMuted(false)
                                            .build()
                                        JitsiMeetActivity.launch(context, options)
                                    } catch (e: Exception) {
                                        status = e.localizedMessage ?: "Could not start the call."
                                    }
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Text("Join voice / video call") }
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Screen sharing", style = MaterialTheme.typography.titleMedium)
                                    Text("During a call, open the More menu and choose Share your screen. Android will show a system permission prompt. Choose Start now to begin sharing; use Stop sharing in the call controls to end it.")
                                    Text("For best clarity, use a stable Wi-Fi connection and keep your phone plugged in for long sessions.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                                }
                            }
                        }
                    }
                    OutlinedButton(onClick = {
                        auth.signOut()
                        user = null
                        selectedFriend = null
                        status = "Signed out."
                    }, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(status, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
