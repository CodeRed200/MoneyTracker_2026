package com.example.moneytracker

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.roundToInt

class GoalsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "GoalsActivity"
        private const val PREFS_PREFIX = "money_tracker_goals_"
        private const val KEY_GOALS = "cached_goals"
        private const val KEY_MOVEMENTS = "cached_goal_movements"
        private const val KEY_PENDING_GOAL_UPSERTS = "pending_goal_upserts"
        private const val KEY_PENDING_GOAL_DELETES = "pending_goal_deletes"
        private const val KEY_PENDING_MOVEMENT_SYNC = "pending_movement_sync"
        private const val KEY_PENDING_MOVEMENT_DELETES = "pending_movement_deletes"
    }

    // Firebase and signed-in user
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private var userId: String = ""
    private val timezone: TimeZone = TimeZone.getTimeZone("Asia/Manila")
    private val prefs by lazy { getSharedPreferences(PREFS_PREFIX + userId, Context.MODE_PRIVATE) }

    // Main views
    private lateinit var goalsScroll: View
    private lateinit var goalFormCard: View
    private lateinit var tvGoalFormTitle: TextView
    private lateinit var etGoalId: EditText
    private lateinit var etGoalName: EditText
    private lateinit var etGoalTarget: EditText
    private lateinit var tvGoalTargetDate: TextView
    private lateinit var etGoalIcon: EditText
    private lateinit var etGoalDescription: EditText
    private lateinit var tvGoalFormError: TextView
    private lateinit var btnSaveGoal: Button

    private lateinit var tvGoalsTotalSaved: TextView
    private lateinit var tvGoalsTotalTarget: TextView
    private lateinit var progressTotalGoals: ProgressBar
    private lateinit var tvGoalsOverallPercentage: TextView
    private lateinit var tvActiveGoalsCount: TextView
    private lateinit var tvCompletedGoalsCount: TextView
    private lateinit var tvGoalsRemainingAmount: TextView

    private lateinit var btnGoalFilter: TextView
    private lateinit var etGoalSearch: EditText
    private lateinit var goalCardsContainer: LinearLayout
    private lateinit var goalsEmptyState: View
    private lateinit var goalsLoading: View
    private lateinit var goalsProgress: ProgressBar
    private lateinit var tvGoalsError: TextView
    private lateinit var tvGoalsSyncStatus: TextView

    private lateinit var spGoalMovementGoal: Spinner
    private lateinit var spGoalMovementType: Spinner
    private lateinit var etGoalMovementAmount: EditText
    private lateinit var tvGoalMovementDate: TextView
    private lateinit var etGoalMovementNotes: EditText
    private lateinit var tvGoalMovementError: TextView
    private lateinit var btnRecordGoalMovement: Button
    private lateinit var goalHistoryLoading: ProgressBar
    private lateinit var tvGoalHistoryEmpty: TextView
    private lateinit var goalHistoryContainer: LinearLayout

    // Data
    private data class SavingsGoal(
        val id: String,
        var name: String,
        var targetAmount: Double,
        var currentSaved: Double,
        var targetDate: Date?,
        var icon: String,
        var description: String,
        var status: String,
        var createdAt: Date,
        var updatedAt: Date
    ) {
        val remaining: Double get() = (targetAmount - currentSaved).coerceAtLeast(0.0)
        val progress: Int get() = if (targetAmount > 0.0) {
            ((currentSaved / targetAmount) * 100.0).roundToInt().coerceIn(0, 100)
        } else 0
    }

    private data class GoalMovement(
        val id: String,
        val goalId: String,
        val goalName: String,
        val amount: Double,
        val type: String,
        val date: Date,
        val notes: String
    )

    private val goals = mutableListOf<SavingsGoal>()
    private val movements = mutableListOf<GoalMovement>()
    private val pendingGoalUpserts = linkedSetOf<String>()
    private val pendingGoalDeletes = linkedSetOf<String>()
    private val pendingMovementSync = mutableListOf<String>()
    private val pendingMovementDeletes = linkedSetOf<String>()

    private var selectedTargetDate: Date? = null
    private var selectedMovementDate: Date = Date()
    private var goalFilter = "All Goals"
    private var isSavingGoal = false
    private var isRecordingMovement = false
    private var isSyncing = false
    private var hasLoadedCloudData = false
    private var movementGoalIds: List<String> = emptyList()

    // Colors
    private val purple = Color.rgb(101, 88, 211)
    private val purpleLight = Color.rgb(239, 237, 255)
    private val green = Color.rgb(20, 155, 113)
    private val greenLight = Color.rgb(231, 247, 240)
    private val red = Color.rgb(216, 93, 104)
    private val redLight = Color.rgb(255, 241, 241)
    private val orange = Color.rgb(213, 138, 54)
    private val orangeLight = Color.rgb(255, 245, 233)
    private val darkText = Color.rgb(32, 35, 52)
    private val mutedText = Color.rgb(133, 139, 158)
    private val borderColor = Color.rgb(235, 237, 243)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_goals)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        initializeViews()
        setupWindowInsets()
        setupClickListeners()
        setupMovementSpinners()
        setupSearch()
        checkUserSession()
    }

    override fun onResume() {
        super.onResume()
        val user = auth.currentUser
        if (user != null && user.isEmailVerified && user.uid == userId && hasLoadedCloudData) {
            syncPendingChanges()
            loadCloudData(showLoading = false)
        }
    }

    private fun initializeViews() {
        goalsScroll = findViewById(R.id.goalsScroll)
        goalFormCard = findViewById(R.id.goalFormCard)
        tvGoalFormTitle = findViewById(R.id.tvGoalFormTitle)
        etGoalId = findViewById(R.id.etGoalId)
        etGoalName = findViewById(R.id.etGoalName)
        etGoalTarget = findViewById(R.id.etGoalTarget)
        tvGoalTargetDate = findViewById(R.id.tvGoalTargetDate)
        etGoalIcon = findViewById(R.id.etGoalIcon)
        etGoalDescription = findViewById(R.id.etGoalDescription)
        tvGoalFormError = findViewById(R.id.tvGoalFormError)
        btnSaveGoal = findViewById(R.id.btnSaveGoal)

        tvGoalsTotalSaved = findViewById(R.id.tvGoalsTotalSaved)
        tvGoalsTotalTarget = findViewById(R.id.tvGoalsTotalTarget)
        progressTotalGoals = findViewById(R.id.progressTotalGoals)
        tvGoalsOverallPercentage = findViewById(R.id.tvGoalsOverallPercentage)
        tvActiveGoalsCount = findViewById(R.id.tvActiveGoalsCount)
        tvCompletedGoalsCount = findViewById(R.id.tvCompletedGoalsCount)
        tvGoalsRemainingAmount = findViewById(R.id.tvGoalsRemainingAmount)

        btnGoalFilter = findViewById(R.id.btnGoalFilter)
        etGoalSearch = findViewById(R.id.etGoalSearch)
        goalCardsContainer = findViewById(R.id.goalCardsContainer)
        goalsEmptyState = findViewById(R.id.goalsEmptyState)
        goalsLoading = findViewById(R.id.goalsLoading)
        goalsProgress = findViewById(R.id.goalsProgress)
        tvGoalsError = findViewById(R.id.tvGoalsError)
        tvGoalsSyncStatus = findViewById(R.id.tvGoalsSyncStatus)

        spGoalMovementGoal = findViewById(R.id.spGoalMovementGoal)
        spGoalMovementType = findViewById(R.id.spGoalMovementType)
        etGoalMovementAmount = findViewById(R.id.etGoalMovementAmount)
        tvGoalMovementDate = findViewById(R.id.tvGoalMovementDate)
        etGoalMovementNotes = findViewById(R.id.etGoalMovementNotes)
        tvGoalMovementError = findViewById(R.id.tvGoalMovementError)
        btnRecordGoalMovement = findViewById(R.id.btnRecordGoalMovement)
        goalHistoryLoading = findViewById(R.id.goalHistoryLoading)
        tvGoalHistoryEmpty = findViewById(R.id.tvGoalHistoryEmpty)
        goalHistoryContainer = findViewById(R.id.goalHistoryContainer)

        selectedMovementDate = Date()
        tvGoalMovementDate.text = formatDate(selectedMovementDate)
        tvGoalMovementDate.setTextColor(darkText)
    }

    private fun setupWindowInsets() {
        val root = findViewById<View>(R.id.main)
        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
            insets
        }
    }

    private fun setupClickListeners() {
        findViewById<View>(R.id.btnGoalsBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnAddGoal).setOnClickListener { showGoalForm() }
        findViewById<View>(R.id.btnCloseGoalForm).setOnClickListener { closeGoalForm() }
        btnSaveGoal.setOnClickListener { saveGoal() }
        tvGoalTargetDate.setOnClickListener { showTargetDatePicker() }
        tvGoalMovementDate.setOnClickListener { showMovementDatePicker() }
        btnRecordGoalMovement.setOnClickListener { recordGoalMovement() }
        btnGoalFilter.setOnClickListener { showGoalFilterDialog() }
    }

    private fun setupMovementSpinners() {
        val movementTypes = listOf("Contribution", "Withdrawal")
        spGoalMovementType.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            movementTypes
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    }

    private fun setupSearch() {
        etGoalSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderGoalCards()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    // Authentication and per-user local cache
    private fun checkUserSession() {
        val user = auth.currentUser
        if (user == null) {
            openLogin()
            return
        }
        if (!user.isEmailVerified) {
            openEmailVerification()
            return
        }
        userId = user.uid
        loadLocalCache()
        renderAll()
        setLoading(true)
        loadCloudData(showLoading = false)
    }

    private fun openLogin() {
        startActivity(Intent(this, Login::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private fun openEmailVerification() {
        startActivity(Intent(this, EmailVerificationActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private fun loadLocalCache() {
        goals.clear()
        movements.clear()
        pendingGoalUpserts.clear()
        pendingGoalDeletes.clear()
        pendingMovementSync.clear()
        pendingMovementDeletes.clear()

        try {
            val goalArray = JSONArray(prefs.getString(KEY_GOALS, "[]") ?: "[]")
            for (i in 0 until goalArray.length()) {
                val json = goalArray.optJSONObject(i) ?: continue
                goals.add(
                    SavingsGoal(
                        id = json.optString("id"),
                        name = json.optString("name"),
                        targetAmount = json.optDouble("targetAmount", 0.0),
                        currentSaved = json.optDouble("currentSaved", 0.0),
                        targetDate = json.optLongOrNull("targetDate")?.let { Date(it) },
                        icon = json.optString("icon", "◎"),
                        description = json.optString("description", ""),
                        status = json.optString("status", "active"),
                        createdAt = Date(json.optLong("createdAt", System.currentTimeMillis())),
                        updatedAt = Date(json.optLong("updatedAt", System.currentTimeMillis()))
                    )
                )
            }

            val movementArray = JSONArray(prefs.getString(KEY_MOVEMENTS, "[]") ?: "[]")
            for (i in 0 until movementArray.length()) {
                val json = movementArray.optJSONObject(i) ?: continue
                movements.add(
                    GoalMovement(
                        id = json.optString("id"),
                        goalId = json.optString("goalId"),
                        goalName = json.optString("goalName"),
                        amount = json.optDouble("amount", 0.0),
                        type = json.optString("type", "contribution"),
                        date = Date(json.optLong("date", System.currentTimeMillis())),
                        notes = json.optString("notes", "")
                    )
                )
            }

            pendingGoalUpserts.addAll(prefs.getStringSet(KEY_PENDING_GOAL_UPSERTS, mutableSetOf()) ?: emptySet())
            pendingGoalDeletes.addAll(prefs.getStringSet(KEY_PENDING_GOAL_DELETES, mutableSetOf()) ?: emptySet())
            pendingMovementSync.addAll(readStringArray(prefs.getString(KEY_PENDING_MOVEMENT_SYNC, "[]")))
            pendingMovementDeletes.addAll(prefs.getStringSet(KEY_PENDING_MOVEMENT_DELETES, mutableSetOf()) ?: emptySet())
        } catch (exception: Exception) {
            Log.e(TAG, "Unable to read local goal cache", exception)
            showError("Some locally cached goal data could not be read.")
        }
    }

    private fun saveLocalCache() {
        val goalArray = JSONArray()
        goals.forEach { goal ->
            goalArray.put(JSONObject().apply {
                put("id", goal.id)
                put("name", goal.name)
                put("targetAmount", goal.targetAmount)
                put("currentSaved", goal.currentSaved)
                put("targetDate", goal.targetDate?.time ?: JSONObject.NULL)
                put("icon", goal.icon)
                put("description", goal.description)
                put("status", goal.status)
                put("createdAt", goal.createdAt.time)
                put("updatedAt", goal.updatedAt.time)
            })
        }

        val movementArray = JSONArray()
        movements.forEach { movement ->
            movementArray.put(JSONObject().apply {
                put("id", movement.id)
                put("goalId", movement.goalId)
                put("goalName", movement.goalName)
                put("amount", movement.amount)
                put("type", movement.type)
                put("date", movement.date.time)
                put("notes", movement.notes)
            })
        }

        prefs.edit()
            .putString(KEY_GOALS, goalArray.toString())
            .putString(KEY_MOVEMENTS, movementArray.toString())
            .putStringSet(KEY_PENDING_GOAL_UPSERTS, HashSet(pendingGoalUpserts))
            .putStringSet(KEY_PENDING_GOAL_DELETES, HashSet(pendingGoalDeletes))
            .putString(KEY_PENDING_MOVEMENT_SYNC, JSONArray(pendingMovementSync).toString())
            .putStringSet(KEY_PENDING_MOVEMENT_DELETES, HashSet(pendingMovementDeletes))
            .apply()
        updateSyncStatus()
    }

    private fun readStringArray(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // Firebase loading. Cached local records are shown immediately while cloud data is requested.
    private fun loadCloudData(showLoading: Boolean) {
        val currentUser = auth.currentUser ?: return
        if (currentUser.uid != userId || !currentUser.isEmailVerified) return
        if (showLoading) setLoading(true)

        val names = listOf("savingsGoals", "savings")
        val snapshots = mutableMapOf<String, QuerySnapshot>()
        var completed = 0
        var failed = 0

        names.forEach { collection ->
            db.collection("users").document(userId).collection(collection).get()
                .addOnSuccessListener { snapshot ->
                    snapshots[collection] = snapshot
                    completed++
                    if (completed == names.size) finishCloudLoad(snapshots, failed)
                }
                .addOnFailureListener { error ->
                    Log.w(TAG, "Could not load $collection", error)
                    failed++
                    completed++
                    if (completed == names.size) finishCloudLoad(snapshots, failed)
                }
        }
    }

    private fun finishCloudLoad(snapshots: Map<String, QuerySnapshot>, failed: Int) {
        mergeCloudData(snapshots)
        hasLoadedCloudData = true
        setLoading(false)
        renderAll()
        if (failed > 0) {
            showError("Some cloud data could not be refreshed. Your saved local records are still available.")
        } else {
            clearError()
        }
        syncPendingChanges()
    }

    private fun mergeCloudData(snapshots: Map<String, QuerySnapshot>) {
        val remoteMovements = mutableListOf<GoalMovement>()
        snapshots["savings"]?.documents?.forEach { doc ->
            val movement = movementFromDocument(doc) ?: return@forEach
            if (movement.goalId.isNotBlank() && movement.id !in pendingMovementDeletes) {
                remoteMovements.add(movement)
            }
        }

        remoteMovements.forEach { remote ->
            val index = movements.indexOfFirst { it.id == remote.id }
            if (index >= 0) {
                if (remote.id !in pendingMovementSync) movements[index] = remote
            } else if (remote.id !in pendingMovementSync) {
                movements.add(remote)
            }
        }

        snapshots["savingsGoals"]?.documents?.forEach { doc ->
            if (doc.id in pendingGoalDeletes || doc.id in pendingGoalUpserts) return@forEach
            val remote = goalFromDocument(doc) ?: return@forEach
            val index = goals.indexOfFirst { it.id == remote.id }
            if (index >= 0) goals[index] = remote else goals.add(remote)
        }

        // Older goal documents may not have a saved-balance field. Derive it from their movement history.
        goals.forEach { goal ->
            val related = movements.filter { it.goalId == goal.id }
            val hasPending = related.any { it.id in pendingMovementSync }
            val remoteDoc = snapshots["savingsGoals"]?.documents?.firstOrNull { it.id == goal.id }
            val hasSavedField = remoteDoc?.contains("currentSaved") == true ||
                    remoteDoc?.contains("savedAmount") == true || remoteDoc?.contains("amountSaved") == true
            if (!hasSavedField && !hasPending && related.isNotEmpty()) {
                goal.currentSaved = related.sumOf { if (it.type.equals("withdrawal", true)) -it.amount else it.amount }.coerceAtLeast(0.0)
            }
        }

        movements.sortByDescending { it.date }
        goals.sortBy { it.name.lowercase(Locale.ROOT) }
        saveLocalCache()
    }

    private fun goalFromDocument(doc: DocumentSnapshot): SavingsGoal? {
        val name = stringFrom(doc, "name", "goalName", "title")
        val target = amountFrom(doc, "targetAmount", "target", "goalAmount")
        if (name.isBlank() || target <= 0.0) return null
        val saved = amountFrom(doc, "currentSaved", "savedAmount", "amountSaved", "saved")
        return SavingsGoal(
            id = doc.id,
            name = name,
            targetAmount = target,
            currentSaved = saved,
            targetDate = dateFrom(doc, "targetDate", "dueDate"),
            icon = stringFrom(doc, "icon", "emoji").ifBlank { "◎" },
            description = stringFrom(doc, "description", "notes"),
            status = stringFrom(doc, "status").ifBlank { if (saved >= target) "completed" else "active" },
            createdAt = dateFrom(doc, "createdAt") ?: Date(),
            updatedAt = dateFrom(doc, "updatedAt") ?: Date()
        )
    }

    private fun movementFromDocument(doc: DocumentSnapshot): GoalMovement? {
        val goalId = stringFrom(doc, "goalId", "savingsGoalId")
        if (goalId.isBlank()) return null
        val amount = amountFrom(doc, "amount", "transactionAmount")
        if (amount <= 0.0) return null
        val type = stringFrom(doc, "transactionType", "movementType", "type")
            .lowercase(Locale.ROOT).let { if (it.contains("withdraw")) "withdrawal" else "contribution" }
        return GoalMovement(
            id = doc.id,
            goalId = goalId,
            goalName = stringFrom(doc, "goalName", "accountName").ifBlank { "Savings goal" },
            amount = amount,
            type = type,
            date = dateFrom(doc, "date", "transactionDate", "createdAt") ?: Date(),
            notes = stringFrom(doc, "notes", "description")
        )
    }

    // Goal form
    private fun showGoalForm(goalId: String? = null) {
        tvGoalFormError.visibility = View.GONE
        if (goalId == null) {
            clearGoalForm()
            tvGoalFormTitle.text = "Create Savings Goal"
            btnSaveGoal.text = "Save Goal"
        } else {
            val goal = goals.firstOrNull { it.id == goalId } ?: return
            etGoalId.setText(goal.id)
            etGoalName.setText(goal.name)
            etGoalTarget.setText(goal.targetAmount.toString())
            etGoalIcon.setText(goal.icon)
            etGoalDescription.setText(goal.description)
            selectedTargetDate = goal.targetDate
            tvGoalTargetDate.text = goal.targetDate?.let { formatDate(it) } ?: "Select target date"
            tvGoalTargetDate.setTextColor(if (goal.targetDate == null) mutedText else darkText)
            tvGoalFormTitle.text = "Edit Savings Goal"
            btnSaveGoal.text = "Update Goal"
        }
        goalFormCard.visibility = View.VISIBLE
        goalsScroll.post { (goalsScroll as? android.widget.ScrollView)?.smoothScrollTo(0, goalFormCard.top) }
    }

    private fun clearGoalForm() {
        etGoalId.setText("")
        etGoalName.setText("")
        etGoalTarget.setText("")
        etGoalIcon.setText("◎")
        etGoalDescription.setText("")
        selectedTargetDate = null
        tvGoalTargetDate.text = "Select target date"
        tvGoalTargetDate.setTextColor(mutedText)
        tvGoalFormError.text = ""
    }

    private fun closeGoalForm() {
        goalFormCard.visibility = View.GONE
        tvGoalFormError.visibility = View.GONE
        hideKeyboard()
    }

    private fun showTargetDatePicker() {
        val calendar = Calendar.getInstance(timezone)
        selectedTargetDate?.let { calendar.time = it }
        DatePickerDialog(
            this,
            { _, year, month, day ->
                val chosen = Calendar.getInstance(timezone).apply {
                    clear()
                    set(year, month, day, 12, 0, 0)
                }
                selectedTargetDate = chosen.time
                tvGoalTargetDate.text = formatDate(chosen.time)
                tvGoalTargetDate.setTextColor(darkText)
                tvGoalFormError.visibility = View.GONE
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun saveGoal() {
        if (isSavingGoal) return
        val name = etGoalName.text.toString().trim()
        val target = parseAmount(etGoalTarget.text.toString())
        val icon = etGoalIcon.text.toString().trim().ifBlank { "◎" }
        val description = etGoalDescription.text.toString().trim()
        val existingId = etGoalId.text.toString().trim()

        val error = when {
            name.isBlank() -> "Please enter a goal name."
            name.length > 100 -> "Goal name must be 100 characters or fewer."
            target <= 0.0 -> "Enter a target amount greater than zero."
            target > 999999999.99 -> "Target amount is too large."
            else -> null
        }
        if (error != null) {
            showFormError(error)
            if (name.isBlank()) etGoalName.requestFocus() else etGoalTarget.requestFocus()
            return
        }

        isSavingGoal = true
        btnSaveGoal.isEnabled = false
        val now = Date()
        val existing = goals.firstOrNull { it.id == existingId }
        val id = existing?.id ?: UUID.randomUUID().toString()
        val saved = existing?.currentSaved ?: 0.0
        val status = when {
            existing?.status.equals("cancelled", true) -> "active"
            saved >= target -> "completed"
            else -> "active"
        }
        val goal = SavingsGoal(
            id = id,
            name = name,
            targetAmount = target,
            currentSaved = saved,
            targetDate = selectedTargetDate,
            icon = icon,
            description = description,
            status = status,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )

        if (existing == null) goals.add(goal) else {
            val index = goals.indexOfFirst { it.id == id }
            goals[index] = goal
        }
        pendingGoalDeletes.remove(id)
        pendingGoalUpserts.add(id)
        saveLocalCache()
        renderAll()
        closeGoalForm()
        isSavingGoal = false
        btnSaveGoal.isEnabled = true
        showToast(if (existing == null) "Savings goal saved on this device." else "Savings goal updated on this device.")
        syncPendingChanges()
    }

    private fun showFormError(message: String) {
        tvGoalFormError.text = message
        tvGoalFormError.visibility = View.VISIBLE
    }

    // Goal filter and cards
    private fun showGoalFilterDialog() {
        val options = arrayOf("All Goals", "Active", "Completed", "Cancelled")
        AlertDialog.Builder(this)
            .setTitle("Filter Savings Goals")
            .setSingleChoiceItems(options, options.indexOf(goalFilter).coerceAtLeast(0)) { dialog, which ->
                goalFilter = options[which]
                btnGoalFilter.text = if (goalFilter == "All Goals") "All Goals ▾" else "$goalFilter ▾"
                dialog.dismiss()
                renderGoalCards()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun renderAll() {
        updateGoalSummary()
        renderGoalCards()
        updateMovementGoalSpinner()
        renderMovementHistory()
        updateSyncStatus()
    }

    private fun updateGoalSummary() {
        val included = goals.filterNot { it.status.equals("cancelled", true) }
        val saved = included.sumOf { it.currentSaved }
        val target = included.sumOf { it.targetAmount }
        val remaining = included.sumOf { it.remaining }
        val active = goals.count { !it.status.equals("cancelled", true) && !it.status.equals("completed", true) }
        val completed = goals.count { it.status.equals("completed", true) }
        val percentage = if (target > 0.0) ((saved / target) * 100.0).roundToInt().coerceIn(0, 100) else 0

        tvGoalsTotalSaved.text = formatCurrency(saved)
        tvGoalsTotalTarget.text = "of ${formatCurrency(target)} total goal targets"
        tvGoalsRemainingAmount.text = formatCurrency(remaining)
        tvActiveGoalsCount.text = active.toString()
        tvCompletedGoalsCount.text = completed.toString()
        progressTotalGoals.max = 100
        progressTotalGoals.progress = percentage
        tvGoalsOverallPercentage.text = "$percentage% overall completion"
    }

    private fun renderGoalCards() {
        goalCardsContainer.removeAllViews()
        val query = etGoalSearch.text.toString().trim().lowercase(Locale.ROOT)
        val filtered = goals.filter { goal ->
            val filterMatches = when (goalFilter) {
                "Active" -> !goal.status.equals("cancelled", true) && !goal.status.equals("completed", true)
                "Completed" -> goal.status.equals("completed", true)
                "Cancelled" -> goal.status.equals("cancelled", true)
                else -> true
            }
            val searchableValues: List<String> = listOf(goal.name, goal.description, goal.status)
            val queryMatches = query.isBlank() || searchableValues.any { value ->
                value.lowercase(Locale.ROOT).contains(query)
            }
            filterMatches && queryMatches
        }.sortedByDescending { it.updatedAt }

        goalsEmptyState.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        filtered.forEach { goalCardsContainer.addView(createGoalCard(it)) }
    }

    private fun createGoalCard(goal: SavingsGoal): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(Color.WHITE, borderColor, 13)
            setPadding(dp(13), dp(13), dp(13), dp(13))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = TextView(this).apply {
            text = goal.icon
            gravity = Gravity.CENTER
            textSize = 23f
            background = roundedBackground(purpleLight, purpleLight, 11)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }
        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
            }
        }
        titleBlock.addView(makeText(goal.name, 13f, darkText, true))
        val targetDateText = goal.targetDate?.let { "Target · ${formatDate(it)}" } ?: "No target date"
        titleBlock.addView(makeText(targetDateText, 10f, mutedText).apply { setPadding(0, dp(4), 0, 0) })
        header.addView(icon)
        header.addView(titleBlock)
        header.addView(statusBadge(goal.status))
        card.addView(header)

        if (goal.description.isNotBlank()) {
            card.addView(makeText(goal.description, 10f, mutedText).apply {
                setPadding(0, dp(11), 0, dp(3))
            })
        }

        val amountRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(13), 0, dp(6))
        }
        amountRow.addView(makeText("${formatCurrency(goal.currentSaved)} saved", 11f, green, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        amountRow.addView(makeText("of ${formatCurrency(goal.targetAmount)}", 10f, mutedText))
        card.addView(amountRow)

        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            this.progress = goal.progress
            progressTintList = android.content.res.ColorStateList.valueOf(if (goal.progress >= 100) green else purple)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(purpleLight)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8))
        }
        card.addView(progress)

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        footer.addView(makeText("${goal.progress}% complete · ${formatCurrency(goal.remaining)} remaining", 9f, mutedText),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        footer.addView(actionText("Edit", purpleLight, purple) { showGoalForm(goal.id) })
        footer.addView(actionText("Delete", redLight, red) { confirmDeleteGoal(goal.id) }.apply {
            setPadding(dp(11), dp(8), dp(11), dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(6)
            }
        })
        card.addView(footer)
        return card
    }

    private fun statusBadge(status: String): TextView {
        val normalized = status.lowercase(Locale.ROOT)
        val label = when (normalized) {
            "completed" -> "Completed"
            "cancelled" -> "Cancelled"
            else -> "Active"
        }
        val bg = when (normalized) {
            "completed" -> greenLight
            "cancelled" -> redLight
            else -> purpleLight
        }
        val fg = when (normalized) {
            "completed" -> green
            "cancelled" -> red
            else -> purple
        }
        return makeText(label, 9f, fg, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = roundedBackground(bg, bg, 20)
        }
    }

    private fun actionText(label: String, backgroundColor: Int, textColor: Int, action: () -> Unit): TextView {
        return makeText(label, 10f, textColor, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(11), dp(8), dp(11), dp(8))
            background = roundedBackground(backgroundColor, backgroundColor, 9)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
    }

    private fun confirmDeleteGoal(goalId: String) {
        val goal = goals.firstOrNull { it.id == goalId } ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete Savings Goal")
            .setMessage("Delete ‘${goal.name}’ and its recorded contribution/withdrawal history? This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> deleteGoal(goalId) }
            .show()
    }

    private fun deleteGoal(goalId: String) {
        // A goal is only a container for a target. Its savings movements are real cash-flow
        // records, so keep them in the shared "savings" collection for Analytics and Dashboard.
        // Pending movements are also allowed to sync as standalone savings records if the goal
        // itself is deleted before the device reconnects.
        goals.removeAll { it.id == goalId }
        pendingGoalUpserts.remove(goalId)
        pendingGoalDeletes.add(goalId)
        saveLocalCache()
        renderAll()
        showToast("Goal deleted. Its savings transaction history was preserved.")
        syncPendingChanges()
    }

    // Goal movement form and local-first recording
    private fun updateMovementGoalSpinner(preferredId: String? = null) {
        val selectable = goals.filterNot { it.status.equals("cancelled", true) }.sortedBy { it.name.lowercase(Locale.ROOT) }
        movementGoalIds = selectable.map { it.id }
        val labels = if (selectable.isEmpty()) listOf("Create a savings goal first") else selectable.map { "${it.icon}  ${it.name}" }
        spGoalMovementGoal.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            labels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spGoalMovementGoal.isEnabled = selectable.isNotEmpty()
        btnRecordGoalMovement.isEnabled = selectable.isNotEmpty() && !isRecordingMovement
        if (preferredId != null) {
            val index = movementGoalIds.indexOf(preferredId)
            if (index >= 0) spGoalMovementGoal.setSelection(index)
        }
    }

    private fun showMovementDatePicker() {
        val calendar = Calendar.getInstance(timezone).apply { time = selectedMovementDate }
        DatePickerDialog(
            this,
            { _, year, month, day ->
                val chosen = Calendar.getInstance(timezone).apply {
                    clear()
                    set(year, month, day, 12, 0, 0)
                }
                selectedMovementDate = chosen.time
                tvGoalMovementDate.text = formatDate(chosen.time)
                tvGoalMovementDate.setTextColor(darkText)
                tvGoalMovementError.visibility = View.GONE
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun recordGoalMovement() {
        if (isRecordingMovement) return

        val spinnerPosition = spGoalMovementGoal.selectedItemPosition
        val goalId = movementGoalIds.getOrNull(spinnerPosition)
        val selectedGoal = goals.firstOrNull { it.id == goalId }

        if (selectedGoal == null) {
            tvGoalMovementError.text = "Create or select a savings goal first."
            tvGoalMovementError.visibility = View.VISIBLE
            showToast("Create or select a savings goal first.")
            return
        }

        val amount = parseAmount(etGoalMovementAmount.text.toString())
        val movementType = if (
            spGoalMovementType.selectedItemPosition == 1
        ) "withdrawal" else "contribution"

        val notes = etGoalMovementNotes.text.toString().trim()

        val error = when {
            amount <= 0.0 ->
                "Enter an amount greater than zero."

            amount > 999999999.99 ->
                "Movement amount is too large."

            movementType == "withdrawal" &&
                    amount > selectedGoal.currentSaved ->
                "You cannot withdraw more than this goal has saved."

            else -> null
        }

        if (error != null) {
            tvGoalMovementError.text = error
            tvGoalMovementError.visibility = View.VISIBLE
            etGoalMovementAmount.requestFocus()
            return
        }

        isRecordingMovement = true
        btnRecordGoalMovement.isEnabled = false
        btnRecordGoalMovement.text = "Saving..."

        val now = Date()

        val movement = GoalMovement(
            id = UUID.randomUUID().toString(),
            goalId = selectedGoal.id,
            goalName = selectedGoal.name,
            amount = amount,
            type = movementType,
            date = selectedMovementDate,
            notes = notes
        )

        val movementDelta = if (movementType == "withdrawal") {
            -amount
        } else {
            amount
        }

        selectedGoal.currentSaved =
            (selectedGoal.currentSaved + movementDelta).coerceAtLeast(0.0)

        selectedGoal.status =
            if (selectedGoal.currentSaved >= selectedGoal.targetAmount) {
                "completed"
            } else {
                "active"
            }

        selectedGoal.updatedAt = now

        movements.add(movement)
        pendingMovementSync.add(movement.id)
        pendingGoalUpserts.add(selectedGoal.id)

        saveLocalCache()

        etGoalMovementAmount.setText("")
        etGoalMovementNotes.setText("")

        selectedMovementDate = Date()
        tvGoalMovementDate.text = formatDate(selectedMovementDate)
        tvGoalMovementDate.setTextColor(darkText)

        tvGoalMovementError.visibility = View.GONE

        hideKeyboard()
        renderAll()

        isRecordingMovement = false
        btnRecordGoalMovement.text = "Record Movement"
        btnRecordGoalMovement.isEnabled = movementGoalIds.isNotEmpty()

        showToast("Goal movement saved on this device.")

        syncPendingChanges()
    }

    private fun renderMovementHistory() {
        goalHistoryContainer.removeAllViews()
        val ordered = movements.sortedByDescending { it.date }
        tvGoalHistoryEmpty.visibility = if (ordered.isEmpty()) View.VISIBLE else View.GONE
        ordered.take(100).forEach { movement ->
            goalHistoryContainer.addView(createMovementRow(movement))
        }
        goalHistoryLoading.visibility = View.GONE
    }

    private fun createMovementRow(movement: GoalMovement): View {
        val isWithdrawal = movement.type.equals("withdrawal", true)
        val accent = if (isWithdrawal) orange else green
        val backgroundColor = if (isWithdrawal) orangeLight else greenLight
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBackground(Color.WHITE, borderColor, 10)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(7) }
        }
        val marker = TextView(this).apply {
            text = if (isWithdrawal) "↑" else "+"
            gravity = Gravity.CENTER
            setTextColor(accent)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            background = roundedBackground(backgroundColor, backgroundColor, 9)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
        }
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(9) }
        }
        details.addView(makeText(movement.goalName, 11f, darkText, true))
        val subtitle = "${if (isWithdrawal) "Withdrawal" else "Contribution"} · ${formatDate(movement.date)}"
        details.addView(makeText(subtitle, 9f, mutedText).apply { setPadding(0, dp(3), 0, 0) })
        if (movement.notes.isNotBlank()) details.addView(makeText(movement.notes, 9f, mutedText).apply { setPadding(0, dp(3), 0, 0) })
        if (movement.id in pendingMovementSync) details.addView(makeText("Waiting for cloud sync", 8f, orange).apply { setPadding(0, dp(3), 0, 0) })
        val value = makeText((if (isWithdrawal) "−" else "+") + formatCurrency(movement.amount), 10f, accent, true)
        row.addView(marker)
        row.addView(details)
        row.addView(value)
        return row
    }

    // Reliable pending-change synchronization. Movement documents use stable IDs and a transaction
    // so retrying after an interrupted sync cannot apply the same contribution/withdrawal twice.
    private fun syncPendingChanges() {
        if (isSyncing || userId.isBlank() || auth.currentUser?.uid != userId) return
        if (pendingGoalUpserts.isEmpty() && pendingGoalDeletes.isEmpty() &&
            pendingMovementSync.isEmpty() && pendingMovementDeletes.isEmpty()) {
            updateSyncStatus()
            return
        }
        isSyncing = true
        val upserts = pendingGoalUpserts.toList()
        syncGoalUpsertAt(upserts, 0) {
            val movementIds = pendingMovementSync.toList()
            syncMovementAt(movementIds, 0) {
                val movementDeleteIds = pendingMovementDeletes.toList()
                syncMovementDeleteAt(movementDeleteIds, 0) {
                    val goalDeleteIds = pendingGoalDeletes.toList()
                    syncGoalDeleteAt(goalDeleteIds, 0) {
                        isSyncing = false
                        saveLocalCache()
                        renderAll()
                    }
                }
            }
        }
    }

    private fun syncGoalUpsertAt(ids: List<String>, index: Int, done: () -> Unit) {
        if (index >= ids.size) {
            done()
            return
        }
        val id = ids[index]
        val goal = goals.firstOrNull { it.id == id }
        if (goal == null) {
            pendingGoalUpserts.remove(id)
            saveLocalCache()
            syncGoalUpsertAt(ids, index + 1, done)
            return
        }
        val includeSavedAmount = movements.none { it.goalId == id && it.id in pendingMovementSync }
        db.collection("users").document(userId).collection("savingsGoals").document(id)
            .set(goalToMap(goal, includeSavedAmount), SetOptions.merge())
            .addOnSuccessListener {
                pendingGoalUpserts.remove(id)
                saveLocalCache()
                syncGoalUpsertAt(ids, index + 1, done)
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "Goal $id remains queued for cloud sync", error)
                syncGoalUpsertAt(ids, index + 1, done)
            }
    }

    private fun goalToMap(goal: SavingsGoal, includeSavedAmount: Boolean): Map<String, Any> {
        val data = hashMapOf<String, Any>(
            "name" to goal.name,
            "goalName" to goal.name,
            "title" to goal.name,
            "targetAmount" to goal.targetAmount,
            "target" to goal.targetAmount,
            "targetDate" to (goal.targetDate?.let { Timestamp(it) } ?: ""),
            "icon" to goal.icon,
            "description" to goal.description,
            "status" to goal.status,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (includeSavedAmount) {
            data["currentSaved"] = goal.currentSaved
            data["savedAmount"] = goal.currentSaved
            data["amountSaved"] = goal.currentSaved
        }
        data["createdAt"] = Timestamp(goal.createdAt)
        return data
    }

    private fun syncMovementAt(ids: List<String>, index: Int, done: () -> Unit) {
        if (index >= ids.size) {
            done()
            return
        }
        val id = ids[index]
        val movement = movements.firstOrNull { it.id == id }
        if (movement == null) {
            pendingMovementSync.remove(id)
            saveLocalCache()
            syncMovementAt(ids, index + 1, done)
            return
        }

        val userRef = db.collection("users").document(userId)
        val movementRef = userRef.collection("savings").document(movement.id)
        val goalRef = userRef.collection("savingsGoals").document(movement.goalId)
        val delta = if (movement.type.equals("withdrawal", true)) -movement.amount else movement.amount
        val movementData = hashMapOf<String, Any>(
            "goalId" to movement.goalId,
            "savingsGoalId" to movement.goalId,
            "goalName" to movement.goalName,
            "amount" to movement.amount,
            "transactionAmount" to movement.amount,
            "transactionType" to movement.type,
            "movementType" to movement.type,
            "type" to movement.type,
            "accountName" to movement.goalName,
            "category" to "Savings Goal",
            "description" to movement.notes.ifBlank { "${movement.type.replaceFirstChar { it.uppercase() }} · ${movement.goalName}" },
            "notes" to movement.notes,
            "date" to Timestamp(movement.date),
            "transactionDate" to Timestamp(movement.date),
            "dateString" to dateKey(movement.date),
            "createdAt" to Timestamp(movement.date),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        db.runTransaction { transaction ->
            val existingMovement = transaction.get(movementRef)
            if (existingMovement.exists()) return@runTransaction null

            val goalSnapshot = transaction.get(goalRef)
            if (!goalSnapshot.exists()) {
                // The user may have deleted the goal while this movement was waiting offline.
                // Preserve the movement as a normal savings cash-flow record rather than losing it.
                transaction.set(movementRef, movementData)
                return@runTransaction null
            }

            val oldSaved = amountFrom(goalSnapshot, "currentSaved", "savedAmount", "amountSaved", "saved")
            val target = amountFrom(goalSnapshot, "targetAmount", "target", "goalAmount")
            val newSaved = oldSaved + delta
            if (newSaved < -0.000001) throw IllegalStateException("Cloud goal balance is too low for this withdrawal.")
            val normalizedSaved = newSaved.coerceAtLeast(0.0)
            val currentStatus = stringFrom(goalSnapshot, "status")
            val newStatus = if (currentStatus.equals("cancelled", true)) "cancelled" else if (target > 0.0 && normalizedSaved >= target) "completed" else "active"

            transaction.set(movementRef, movementData)
            transaction.update(goalRef, mapOf(
                "currentSaved" to normalizedSaved,
                "savedAmount" to normalizedSaved,
                "amountSaved" to normalizedSaved,
                "status" to newStatus,
                "updatedAt" to FieldValue.serverTimestamp()
            ))
            null
        }.addOnSuccessListener {
            pendingMovementSync.remove(id)
            saveLocalCache()
            syncMovementAt(ids, index + 1, done)
        }.addOnFailureListener { error ->
            // Keep the operation in the local pending queue for the next online retry.
            Log.w(TAG, "Movement $id remains queued for cloud sync", error)
            syncMovementAt(ids, index + 1, done)
        }
    }

    private fun syncMovementDeleteAt(ids: List<String>, index: Int, done: () -> Unit) {
        if (index >= ids.size) {
            done()
            return
        }
        val id = ids[index]
        db.collection("users").document(userId).collection("savings").document(id)
            .delete()
            .addOnSuccessListener {
                pendingMovementDeletes.remove(id)
                saveLocalCache()
                syncMovementDeleteAt(ids, index + 1, done)
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "Movement deletion $id remains queued", error)
                syncMovementDeleteAt(ids, index + 1, done)
            }
    }

    private fun syncGoalDeleteAt(ids: List<String>, index: Int, done: () -> Unit) {
        if (index >= ids.size) {
            done()
            return
        }
        val id = ids[index]
        db.collection("users").document(userId).collection("savingsGoals").document(id)
            .delete()
            .addOnSuccessListener {
                pendingGoalDeletes.remove(id)
                saveLocalCache()
                syncGoalDeleteAt(ids, index + 1, done)
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "Goal deletion $id remains queued", error)
                syncGoalDeleteAt(ids, index + 1, done)
            }
    }

    private fun updateSyncStatus() {
        if (!::tvGoalsSyncStatus.isInitialized || userId.isBlank()) return
        val pending = pendingGoalUpserts.size + pendingGoalDeletes.size + pendingMovementSync.size + pendingMovementDeletes.size
        tvGoalsSyncStatus.text = if (pending > 0) {
            "Saved locally · $pending change(s) waiting to sync"
        } else {
            "Synced with your Firebase account"
        }
        tvGoalsSyncStatus.setTextColor(if (pending > 0) orange else green)
    }

    // Firestore and JSON helpers
    private fun amountFrom(document: DocumentSnapshot, vararg fields: String): Double {
        for (field in fields) {
            if (document.contains(field)) return parseAmount(document.get(field))
        }
        return 0.0
    }

    private fun stringFrom(document: DocumentSnapshot, vararg fields: String): String {
        for (field in fields) {
            val value = document.get(field)
            if (value is String && value.isNotBlank()) return value.trim()
        }
        return ""
    }

    private fun dateFrom(document: DocumentSnapshot, vararg fields: String): Date? {
        for (field in fields) {
            when (val value = document.get(field)) {
                is Timestamp -> return value.toDate()
                is Date -> return value
                is Number -> {
                    val raw = value.toLong()
                    return Date(if (raw < 100_000_000_000L) raw * 1000L else raw)
                }
                is String -> parseDate(value)?.let { return it }
            }
        }
        return null
    }

    private fun parseAmount(raw: String): Double = parseAmount(raw as Any?)

    private fun parseAmount(value: Any?): Double {
        return when (value) {
            is Number -> value.toDouble()
            is String -> {
                val text = value.trim()
                if (text.isBlank()) return 0.0
                val negative = text.startsWith("(") && text.endsWith(")")
                val parsed = text.replace(Regex("[^0-9.\\-]"), "").toDoubleOrNull() ?: return 0.0
                if (negative && parsed > 0.0) -parsed else parsed
            }
            else -> 0.0
        }
    }

    private fun parseDate(value: String): Date? {
        val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd", "dd/MM/yyyy", "MM/dd/yyyy", "MMM d, yyyy", "MMMM d, yyyy")
        patterns.forEach { pattern ->
            try {
                val formatter = SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    timeZone = timezone
                }
                formatter.parse(value)?.let { return it }
            } catch (_: Exception) {
                // Try another supported date pattern.
            }
        }
        return null
    }

    private fun JSONObject.optLongOrNull(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return optLong(key).takeIf { it > 0L }
    }

    // UI helpers
    private fun setLoading(loading: Boolean) {
        goalsLoading.visibility = if (loading && goals.isEmpty()) View.VISIBLE else View.GONE
        goalsProgress.visibility = if (loading) View.VISIBLE else View.GONE
        goalHistoryLoading.visibility = if (loading && movements.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showError(message: String) {
        tvGoalsError.text = message
        tvGoalsError.visibility = View.VISIBLE
    }

    private fun clearError() {
        tvGoalsError.text = ""
        tvGoalsError.visibility = View.GONE
    }

    private fun hideKeyboard() {
        val input = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        input?.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
        currentFocus?.clearFocus()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun makeText(textValue: String, size: Float, color: Int, bold: Boolean = false): TextView {
        return TextView(this).apply {
            text = textValue
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
    }

    private fun roundedBackground(fillColor: Int, strokeColor: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), strokeColor)
        }
    }

    private fun formatCurrency(amount: Double): String = NumberFormat.getCurrencyInstance(Locale("en", "PH")).format(amount)

    private fun formatDate(date: Date): String = SimpleDateFormat("MMM d, yyyy", Locale.US).apply { timeZone = timezone }.format(date)

    private fun dateKey(date: Date): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = timezone }.format(date)

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()
}
