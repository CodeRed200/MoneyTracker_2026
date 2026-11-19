package com.example.moneytracker

import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
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

class SalaryActivity : AppCompatActivity() {

    // -------------------------------------------------------------------------
    // Models
    // -------------------------------------------------------------------------

    private data class SalaryRecord(
        val id: String,
        var date: Long,
        var period: String,
        var gross: Double,
        var allowances: Double,
        var overtime: Double,
        var sss: Double,
        var philhealth: Double,
        var pagibig: Double,
        var tax: Double,
        var otherDeductions: Double,
        var notes: String,
        var updatedAt: Long = System.currentTimeMillis()
    ) {
        val totalEarnings: Double
            get() = gross + allowances + overtime

        val totalDeductions: Double
            get() = sss + philhealth + pagibig + tax + otherDeductions

        val netSalary: Double
            get() = totalEarnings - totalDeductions
    }

    private data class PendingOperation(
        val id: String,
        val action: String,
        val updatedAt: Long
    )

    // -------------------------------------------------------------------------
    // Firebase and local storage
    // -------------------------------------------------------------------------

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private var userId: String? = null
    private var redirectedToAuth = false

    private val salaryRecords = mutableListOf<SalaryRecord>()
    private val pendingOperations = linkedMapOf<String, PendingOperation>()
    private val operationsInFlight = mutableSetOf<String>()

    private val prefs by lazy {
        getSharedPreferences("money_tracker_salary", MODE_PRIVATE)
    }

    private fun recordsStorageKey(uid: String) = "salary_records_$uid"
    private fun pendingStorageKey(uid: String) = "salary_pending_$uid"

    private val manilaTimeZone: TimeZone = TimeZone.getTimeZone("Asia/Manila")

    private val currencyFormatter =
        NumberFormat.getCurrencyInstance(Locale("en", "PH"))

    private val monthFormatter = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).apply {
        timeZone = manilaTimeZone
    }

    private val dateFormatter = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).apply {
        timeZone = manilaTimeZone
    }

    private val isoDateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = manilaTimeZone
    }

    private val salaryCalendar = Calendar.getInstance(manilaTimeZone)
    private var selectedSalaryDate: Long? = null
    private var hasLoadedCloud = false
    private var isSaving = false

    private val firstPeriod = "15th Salary (15th–End)"
    private val secondPeriod = "Month-end Salary (1st–14th)"

    private companion object {
        const val ACTION_UPSERT = "upsert"
        const val ACTION_DELETE = "delete"
        const val LEGACY_MIGRATED_KEY = "salary_legacy_migrated"
    }

    // -------------------------------------------------------------------------
    // Views
    // -------------------------------------------------------------------------

    private lateinit var salaryFormCard: View
    private lateinit var etSalaryId: EditText
    private lateinit var etSalaryGross: EditText
    private lateinit var etSalaryAllowances: EditText
    private lateinit var etSalaryOvertime: EditText
    private lateinit var etSalarySSS: EditText
    private lateinit var etSalaryPhilhealth: EditText
    private lateinit var etSalaryPagibig: EditText
    private lateinit var etSalaryTax: EditText
    private lateinit var etSalaryOtherDeductions: EditText
    private lateinit var etSalaryNotes: EditText
    private lateinit var etSalarySearch: EditText
    private lateinit var spSalaryPeriod: Spinner
    private lateinit var tvSalaryDate: TextView
    private lateinit var tvSalaryPeriodExplanation: TextView
    private lateinit var tvSalaryFormError: TextView
    private lateinit var tvSalarySyncStatus: TextView
    private lateinit var tvSalaryError: TextView

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_salary)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        initializeViews()
        setupWindowInsets()
        setupSpinners()
        setupClickListeners()
        setupTextWatchers()

        if (!requireAuthenticatedUser()) return

        val uid = userId ?: return
        migrateLegacyLocalRecords(uid)
        loadLocalRecords(uid)
        updateMonthDisplay()
        renderAll()
        updateSyncStatus("Saved on this device · Checking Firebase…")

        if (intent.getBooleanExtra("open_form", false)) {
            showSalaryForm()
        }

        loadCloudRecords()
        syncPendingOperations()
    }

    override fun onResume() {
        super.onResume()
        if (!::auth.isInitialized || userId == null || redirectedToAuth) return

        val currentUser = auth.currentUser
        if (currentUser == null || currentUser.uid != userId) {
            redirectToLogin()
            return
        }

        // Firestore queues offline writes automatically. This also retries any
        // locally queued operations that previously failed before being queued.
        syncPendingOperations()
    }

    private fun requireAuthenticatedUser(): Boolean {
        val user = auth.currentUser
        if (user == null) {
            redirectToLogin()
            return false
        }
        if (!user.isEmailVerified) {
            redirectToEmailVerification()
            return false
        }
        userId = user.uid
        return true
    }

    private fun redirectToLogin() {
        if (redirectedToAuth) return
        redirectedToAuth = true
        startActivity(Intent(this, Login::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private fun redirectToEmailVerification() {
        if (redirectedToAuth) return
        redirectedToAuth = true
        startActivity(Intent(this, EmailVerificationActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    // -------------------------------------------------------------------------
    // Initial setup
    // -------------------------------------------------------------------------

    private fun initializeViews() {
        salaryFormCard = findViewById(R.id.salaryFormCard)
        etSalaryId = findViewById(R.id.etSalaryId)
        etSalaryGross = findViewById(R.id.etSalaryGross)
        etSalaryAllowances = findViewById(R.id.etSalaryAllowances)
        etSalaryOvertime = findViewById(R.id.etSalaryOvertime)
        etSalarySSS = findViewById(R.id.etSalarySSS)
        etSalaryPhilhealth = findViewById(R.id.etSalaryPhilhealth)
        etSalaryPagibig = findViewById(R.id.etSalaryPagibig)
        etSalaryTax = findViewById(R.id.etSalaryTax)
        etSalaryOtherDeductions = findViewById(R.id.etSalaryOtherDeductions)
        etSalaryNotes = findViewById(R.id.etSalaryNotes)
        etSalarySearch = findViewById(R.id.etSalarySearch)
        spSalaryPeriod = findViewById(R.id.spSalaryPeriod)
        tvSalaryDate = findViewById(R.id.tvSalaryDate)
        tvSalaryPeriodExplanation = findViewById(R.id.tvSalaryPeriodExplanation)
        tvSalaryFormError = findViewById(R.id.tvSalaryFormError)
        tvSalarySyncStatus = findViewById(R.id.tvSalarySyncStatus)
        tvSalaryError = findViewById(R.id.tvSalaryError)
    }

    private fun setupWindowInsets() {
        val rootView = findViewById<View>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun setupSpinners() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            listOf(firstPeriod, secondPeriod)
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spSalaryPeriod.adapter = adapter
        spSalaryPeriod.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) = updateSalaryPeriodExplanation()

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun updateSalaryPeriodExplanation() {
        tvSalaryPeriodExplanation.text = when (spSalaryPeriod.selectedItemPosition) {
            0 -> "15th salary covers the 15th through the end of the current month."
            1 -> "Month-end salary covers the 1st through the 14th of the following month."
            else -> "Select the salary period covered by this payment."
        }
    }

    private fun setupClickListeners() {
        findViewById<View>(R.id.btnSalaryBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnRefreshSalary).setOnClickListener { refreshSalaryRecords() }
        findViewById<View>(R.id.btnAddSalary).setOnClickListener { showSalaryForm() }
        findViewById<View>(R.id.btnCloseSalaryForm).setOnClickListener {
            salaryFormCard.visibility = View.GONE
        }
        findViewById<View>(R.id.btnSaveSalary).setOnClickListener { saveSalary() }
        tvSalaryDate.setOnClickListener { showSalaryDatePicker() }

        findViewById<View>(R.id.btnPreviousSalaryMonth).setOnClickListener {
            salaryCalendar.add(Calendar.MONTH, -1)
            updateMonthDisplay()
            renderAll()
        }

        findViewById<View>(R.id.btnNextSalaryMonth).setOnClickListener {
            val next = salaryCalendar.clone() as Calendar
            next.add(Calendar.MONTH, 1)
            val today = Calendar.getInstance(manilaTimeZone)
            val isFutureMonth = next.get(Calendar.YEAR) > today.get(Calendar.YEAR) ||
                    (next.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                            next.get(Calendar.MONTH) > today.get(Calendar.MONTH))

            if (isFutureMonth) {
                showMessage("Future salary periods are not available yet.")
                return@setOnClickListener
            }

            salaryCalendar.add(Calendar.MONTH, 1)
            updateMonthDisplay()
            renderAll()
        }

        findViewById<View>(R.id.btnClearSalarySearch).setOnClickListener {
            etSalarySearch.setText("")
        }

        findViewById<View>(R.id.btnViewFirstPayday).setOnClickListener {
            showPaydayDetails(firstPeriod)
        }

        findViewById<View>(R.id.btnViewSecondPayday).setOnClickListener {
            showPaydayDetails(secondPeriod)
        }
    }

    private fun setupTextWatchers() {
        val calculationFields = listOf(
            etSalaryGross,
            etSalaryAllowances,
            etSalaryOvertime,
            etSalarySSS,
            etSalaryPhilhealth,
            etSalaryPagibig,
            etSalaryTax,
            etSalaryOtherDeductions
        )

        calculationFields.forEach { field ->
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    updateSalaryCalculation()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }

        etSalarySearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderSalaryRecords()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    // -------------------------------------------------------------------------
    // Local storage: SharedPreferences mirror + migration from the old version
    // -------------------------------------------------------------------------

    private fun migrateLegacyLocalRecords(uid: String) {
        if (prefs.getBoolean(LEGACY_MIGRATED_KEY, false)) return

        val newKey = recordsStorageKey(uid)
        if (prefs.contains(newKey)) {
            prefs.edit().putBoolean(LEGACY_MIGRATED_KEY, true).apply()
            return
        }

        val legacyJson = prefs.getString("salary_records", null)
        if (legacyJson.isNullOrBlank()) {
            prefs.edit().putBoolean(LEGACY_MIGRATED_KEY, true).apply()
            return
        }

        try {
            val array = JSONArray(legacyJson)
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val id = json.optString("id").ifBlank { UUID.randomUUID().toString() }
                val record = recordFromJson(json, id) ?: continue
                salaryRecords.add(record)
                pendingOperations[id] = PendingOperation(id, ACTION_UPSERT, record.updatedAt)
            }
            saveLocalState(uid)
            prefs.edit().putBoolean(LEGACY_MIGRATED_KEY, true).apply()
        } catch (exception: Exception) {
            showDataError("Unable to migrate saved salary records: ${exception.localizedMessage ?: "Invalid local data"}")
        }
    }

    private fun loadLocalRecords(uid: String) {
        salaryRecords.clear()
        pendingOperations.clear()

        try {
            val recordsArray = JSONArray(prefs.getString(recordsStorageKey(uid), "[]"))
            for (index in 0 until recordsArray.length()) {
                val json = recordsArray.optJSONObject(index) ?: continue
                val id = json.optString("id")
                if (id.isBlank()) continue
                recordFromJson(json, id)?.let { salaryRecords.add(it) }
            }

            val pendingArray = JSONArray(prefs.getString(pendingStorageKey(uid), "[]"))
            for (index in 0 until pendingArray.length()) {
                val json = pendingArray.optJSONObject(index) ?: continue
                val id = json.optString("id")
                val action = json.optString("action")
                if (id.isNotBlank() && action in listOf(ACTION_UPSERT, ACTION_DELETE)) {
                    pendingOperations[id] = PendingOperation(
                        id = id,
                        action = action,
                        updatedAt = json.optLong("updatedAt", 0L)
                    )
                }
            }
        } catch (exception: Exception) {
            showDataError("Unable to load local salary records: ${exception.localizedMessage ?: "Invalid local data"}")
        }
    }

    private fun recordFromJson(json: JSONObject, id: String): SalaryRecord? {
        val date = json.optLong("date", 0L)
        if (date <= 0L) return null
        return SalaryRecord(
            id = id,
            date = date,
            period = json.optString("period", firstPeriod),
            gross = json.optDouble("gross", 0.0),
            allowances = json.optDouble("allowances", 0.0),
            overtime = json.optDouble("overtime", 0.0),
            sss = json.optDouble("sss", 0.0),
            philhealth = json.optDouble("philhealth", 0.0),
            pagibig = json.optDouble("pagibig", 0.0),
            tax = json.optDouble("tax", 0.0),
            otherDeductions = json.optDouble("otherDeductions", 0.0),
            notes = json.optString("notes", ""),
            updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
        )
    }

    private fun saveLocalState(uid: String) {
        val recordsArray = JSONArray()
        salaryRecords.forEach { record ->
            recordsArray.put(JSONObject().apply {
                put("id", record.id)
                put("date", record.date)
                put("period", record.period)
                put("gross", record.gross)
                put("allowances", record.allowances)
                put("overtime", record.overtime)
                put("sss", record.sss)
                put("philhealth", record.philhealth)
                put("pagibig", record.pagibig)
                put("tax", record.tax)
                put("otherDeductions", record.otherDeductions)
                put("notes", record.notes)
                put("updatedAt", record.updatedAt)
            })
        }

        val pendingArray = JSONArray()
        pendingOperations.values.forEach { operation ->
            pendingArray.put(JSONObject().apply {
                put("id", operation.id)
                put("action", operation.action)
                put("updatedAt", operation.updatedAt)
            })
        }

        prefs.edit()
            .putString(recordsStorageKey(uid), recordsArray.toString())
            .putString(pendingStorageKey(uid), pendingArray.toString())
            .apply()
    }

    // -------------------------------------------------------------------------
    // Firebase load and merge
    // -------------------------------------------------------------------------

    private fun salaryCollection(): CollectionReference? {
        val uid = userId ?: return null
        return db.collection("users").document(uid).collection("salaries")
    }

    private fun refreshSalaryRecords() {
        if (userId.isNullOrBlank()) {
            redirectToLogin()
            return
        }

        findViewById<View>(R.id.salaryProgress).visibility = View.VISIBLE
        updateSyncStatus("Refreshing salary records from Firebase…")
        clearDataError()
        loadCloudRecords()
        syncPendingOperations()
    }

    private fun loadCloudRecords() {
        val collection = salaryCollection()
        if (collection == null) {
            findViewById<View>(R.id.salaryProgress).visibility = View.GONE
            updateSyncStatus("Local only · Sign in to sync")
            return
        }

        findViewById<View>(R.id.salaryProgress).visibility = View.VISIBLE
        collection.get()
            .addOnSuccessListener { snapshot ->
                findViewById<View>(R.id.salaryProgress).visibility = View.GONE
                hasLoadedCloud = true
                mergeCloudRecords(snapshot)
                renderAll()
                clearDataError()

                if (snapshot.metadata.isFromCache) {
                    updateSyncStatus("Showing device / Firebase cached data · Offline mode")
                } else if (pendingOperations.isEmpty()) {
                    updateSyncStatus("Synced with Firebase")
                } else {
                    updateSyncStatus("Firebase connected · Syncing pending changes…")
                }

                syncPendingOperations()
            }
            .addOnFailureListener { exception ->
                findViewById<View>(R.id.salaryProgress).visibility = View.GONE
                updateSyncStatus("Offline · Changes are saved on this device")
                showDataError("Could not refresh salary records from Firebase. Your local records are still available. ${exception.localizedMessage ?: ""}")
            }
    }

    private fun mergeCloudRecords(snapshot: QuerySnapshot) {
        val authoritativeServerSnapshot = !snapshot.metadata.isFromCache
        val remoteRecords = linkedMapOf<String, SalaryRecord>()

        snapshot.documents.forEach { document ->
            recordFromDocument(document)?.let { remoteRecords[it.id] = it }
        }

        val localRecords = salaryRecords.associateBy { it.id }
        val merged = linkedMapOf<String, SalaryRecord>()

        // Start with Firebase records, except records that have a local delete queued.
        remoteRecords.forEach { (id, record) ->
            val pending = pendingOperations[id]
            if (pending?.action != ACTION_DELETE) {
                merged[id] = record
            }
        }

        // Local pending edits always win. Unqueued local records are retained only
        // when Firebase returned cache data; a complete server snapshot is authoritative.
        localRecords.forEach { (id, localRecord) ->
            when (pendingOperations[id]?.action) {
                ACTION_DELETE -> merged.remove(id)
                ACTION_UPSERT -> merged[id] = localRecord
                else -> {
                    if (remoteRecords.containsKey(id)) {
                        merged[id] = remoteRecords[id] ?: localRecord
                    } else if (!authoritativeServerSnapshot) {
                        merged[id] = localRecord
                    }
                }
            }
        }

        salaryRecords.clear()
        salaryRecords.addAll(merged.values.sortedByDescending { it.date })
        userId?.let { saveLocalState(it) }
    }

    private fun recordFromDocument(document: DocumentSnapshot): SalaryRecord? {
        val dateMillis = readDateMillis(document) ?: return null
        val id = document.getString("id")?.takeIf { it.isNotBlank() } ?: document.id
        val rawPeriod = document.getString("period")
            ?: document.getString("salaryPeriod")
            ?: document.getString("paydayPeriodLabel")
            ?: ""
        val period = when {
            rawPeriod == "month_end_salary" -> secondPeriod
            rawPeriod == "15th_salary" -> firstPeriod
            rawPeriod.contains("month-end", ignoreCase = true) -> secondPeriod
            rawPeriod.contains("1st", ignoreCase = true) -> secondPeriod
            rawPeriod.contains("15th", ignoreCase = true) -> firstPeriod
            else -> rawPeriod.ifBlank { firstPeriod }
        }
        val updatedAt = (document.get("localUpdatedAt") as? Number)?.toLong()
            ?: document.getTimestamp("updatedAt")?.toDate()?.time
            ?: dateMillis

        return SalaryRecord(
            id = id,
            date = dateMillis,
            period = period,
            gross = documentAmount(document, "gross", "grossSalary", "basicSalary"),
            allowances = documentAmount(document, "allowances"),
            overtime = documentAmount(document, "overtime", "bonus"),
            sss = documentAmount(document, "sss"),
            philhealth = documentAmount(document, "philhealth", "philHealth"),
            pagibig = documentAmount(document, "pagibig", "pagIbig"),
            tax = documentAmount(document, "tax", "incomeTax"),
            otherDeductions = documentAmount(document, "otherDeductions"),
            notes = document.getString("notes") ?: "",
            updatedAt = updatedAt
        )
    }

    private fun readDateMillis(document: DocumentSnapshot): Long? {
        val fields = listOf("date", "salaryDate", "paymentDate", "dateString")
        for (field in fields) {
            when (val value = document.get(field)) {
                is Timestamp -> return value.toDate().time
                is Date -> return value.time
                is Number -> {
                    val raw = value.toLong()
                    return if (raw < 100000000000L) raw * 1000L else raw
                }
                is String -> parseDate(value)?.let { return it.time }
            }
        }
        return null
    }

    private fun documentAmount(document: DocumentSnapshot, vararg fields: String): Double {
        for (field in fields) {
            val parsed = parseAmount(document.get(field))
            if (parsed != null) return parsed
        }
        return 0.0
    }

    // -------------------------------------------------------------------------
    // Firebase sync queue: local save first, then cloud upsert / delete
    // -------------------------------------------------------------------------

    private fun markPendingUpsert(record: SalaryRecord) {
        pendingOperations[record.id] = PendingOperation(
            id = record.id,
            action = ACTION_UPSERT,
            updatedAt = record.updatedAt
        )
        userId?.let { saveLocalState(it) }
    }

    private fun markPendingDelete(id: String) {
        val timestamp = System.currentTimeMillis()
        pendingOperations[id] = PendingOperation(id, ACTION_DELETE, timestamp)
        userId?.let { saveLocalState(it) }
    }

    private fun syncPendingOperations() {
        val collection = salaryCollection() ?: return
        if (pendingOperations.isEmpty()) {
            if (hasLoadedCloud) updateSyncStatus("Synced with Firebase")
            return
        }

        pendingOperations.values.toList().forEach { operation ->
            if (!operationsInFlight.add(operation.id)) return@forEach

            when (operation.action) {
                ACTION_UPSERT -> {
                    val record = salaryRecords.firstOrNull { it.id == operation.id }
                    if (record == null) {
                        operationsInFlight.remove(operation.id)
                        return@forEach
                    }

                    collection.document(record.id)
                        .set(recordToFirestoreMap(record), SetOptions.merge())
                        .addOnSuccessListener {
                            operationsInFlight.remove(operation.id)
                            removePendingIfUnchanged(operation)
                        }
                        .addOnFailureListener { exception ->
                            operationsInFlight.remove(operation.id)
                            updateSyncStatus("Saved locally · Firebase sync will retry")
                            android.util.Log.w(
                                "SalaryActivity",
                                "Salary ${record.id} remains queued for Firebase sync",
                                exception
                            )
                        }
                }

                ACTION_DELETE -> {
                    collection.document(operation.id)
                        .delete()
                        .addOnSuccessListener {
                            operationsInFlight.remove(operation.id)
                            removePendingIfUnchanged(operation)
                        }
                        .addOnFailureListener { exception ->
                            operationsInFlight.remove(operation.id)
                            updateSyncStatus("Delete saved locally · Firebase sync will retry")
                            android.util.Log.w(
                                "SalaryActivity",
                                "Salary delete ${operation.id} remains queued for Firebase sync",
                                exception
                            )
                        }
                }
            }
        }

        updateSyncStatus("Saved on this device · Syncing ${pendingOperations.size} change(s)…")
    }

    private fun recordToFirestoreMap(record: SalaryRecord): HashMap<String, Any> {
        val paydayPeriod = if (record.period == secondPeriod) "month_end_salary" else "15th_salary"
        val paydayLabel = if (record.period == secondPeriod) "Month-end salary period" else "15th salary period"
        val net = record.netSalary

        return hashMapOf(
            "id" to record.id,
            "type" to "salary",
            "description" to "Salary received - ${record.period}",
            "salaryName" to "Salary received",
            "date" to Timestamp(Date(record.date)),
            "dateString" to isoDateFormatter.format(Date(record.date)),
            "salaryDate" to Timestamp(Date(record.date)),
            "period" to record.period,
            "salaryPeriod" to record.period,
            "paydayPeriod" to paydayPeriod,
            "paydayPeriodLabel" to paydayLabel,
            "gross" to record.gross,
            "grossSalary" to record.gross,
            "allowances" to record.allowances,
            "overtime" to record.overtime,
            "sss" to record.sss,
            "philhealth" to record.philhealth,
            "pagibig" to record.pagibig,
            "tax" to record.tax,
            "otherDeductions" to record.otherDeductions,
            "totalEarnings" to record.totalEarnings,
            "totalDeductions" to record.totalDeductions,
            "netSalary" to net,
            "netAmount" to net,
            "netPay" to net,
            "takeHomePay" to net,
            "amount" to net,
            "notes" to record.notes,
            "localUpdatedAt" to record.updatedAt,
            "updatedAt" to FieldValue.serverTimestamp()
        )
    }

    private fun removePendingIfUnchanged(completed: PendingOperation) {
        val current = pendingOperations[completed.id]
        if (current != null && current.action == completed.action && current.updatedAt == completed.updatedAt) {
            pendingOperations.remove(completed.id)
            userId?.let { saveLocalState(it) }
        }

        if (pendingOperations.isEmpty()) {
            updateSyncStatus("Synced with Firebase")
        } else {
            updateSyncStatus("Firebase sync pending · ${pendingOperations.size} change(s) left")
            // An edit may have been made while an older write for the same ID
            // was already in flight. Start syncing that newer version now.
            syncPendingOperations()
        }
    }

    // -------------------------------------------------------------------------
    // Month / salary form
    // -------------------------------------------------------------------------

    private fun updateMonthDisplay() {
        findViewById<TextView>(R.id.tvSalaryMonth).text = monthFormatter.format(salaryCalendar.time)
    }

    private fun getSelectedMonthRecords(): List<SalaryRecord> {
        return salaryRecords.filter { record ->
            val recordDate = Calendar.getInstance(manilaTimeZone).apply {
                timeInMillis = record.date
            }
            recordDate.get(Calendar.YEAR) == salaryCalendar.get(Calendar.YEAR) &&
                    recordDate.get(Calendar.MONTH) == salaryCalendar.get(Calendar.MONTH)
        }
    }

    private fun showSalaryForm(id: String? = null) {
        tvSalaryFormError.visibility = View.GONE

        if (id == null) {
            clearSalaryForm()
            findViewById<TextView>(R.id.tvSalaryFormTitle).text = "Add Salary Record"
        } else {
            val record = salaryRecords.find { it.id == id } ?: return
            etSalaryId.setText(record.id)
            etSalaryGross.setText(record.gross.toString())
            etSalaryAllowances.setText(record.allowances.toString())
            etSalaryOvertime.setText(record.overtime.toString())
            etSalarySSS.setText(record.sss.toString())
            etSalaryPhilhealth.setText(record.philhealth.toString())
            etSalaryPagibig.setText(record.pagibig.toString())
            etSalaryTax.setText(record.tax.toString())
            etSalaryOtherDeductions.setText(record.otherDeductions.toString())
            etSalaryNotes.setText(record.notes)
            selectedSalaryDate = record.date
            tvSalaryDate.text = formatDate(record.date)
            spSalaryPeriod.setSelection(if (record.period == secondPeriod) 1 else 0)
            findViewById<TextView>(R.id.tvSalaryFormTitle).text = "Edit Salary Record"
        }

        updateSalaryCalculation()
        salaryFormCard.visibility = View.VISIBLE
        salaryFormCard.post {
            findViewById<ScrollView>(R.id.salaryScroll).smoothScrollTo(0, salaryFormCard.top)
        }
    }

    private fun clearSalaryForm() {
        etSalaryId.setText("")
        etSalaryGross.setText("0")
        etSalaryAllowances.setText("0")
        etSalaryOvertime.setText("0")
        etSalarySSS.setText("0")
        etSalaryPhilhealth.setText("0")
        etSalaryPagibig.setText("0")
        etSalaryTax.setText("0")
        etSalaryOtherDeductions.setText("0")
        etSalaryNotes.setText("")
        selectedSalaryDate = null
        tvSalaryDate.text = "Select payment date"
        spSalaryPeriod.setSelection(0)
        updateSalaryCalculation()
    }

    private fun showSalaryDatePicker() {
        val calendar = Calendar.getInstance(manilaTimeZone)
        selectedSalaryDate?.let { calendar.timeInMillis = it }

        DatePickerDialog(
            this,
            { _, year, month, day ->
                val selected = Calendar.getInstance(manilaTimeZone).apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, day)
                    set(Calendar.HOUR_OF_DAY, 12)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                selectedSalaryDate = selected.timeInMillis
                tvSalaryDate.text = formatDate(selected.timeInMillis)
                tvSalaryFormError.visibility = View.GONE
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun updateSalaryCalculation() {
        val earnings = getAmount(etSalaryGross) + getAmount(etSalaryAllowances) + getAmount(etSalaryOvertime)
        val deductions = getTotalDeductionsFromForm()
        val net = earnings - deductions

        findViewById<TextView>(R.id.tvCalcEarnings).text = formatMoney(earnings)
        findViewById<TextView>(R.id.tvCalcDeductions).text = formatMoney(deductions)
        findViewById<TextView>(R.id.tvCalcNet).apply {
            text = formatMoney(net)
            setTextColor(Color.parseColor(if (net >= 0) "#6558D3" else "#D85D68"))
        }
    }

    private fun getTotalDeductionsFromForm(): Double {
        return getAmount(etSalarySSS) + getAmount(etSalaryPhilhealth) +
                getAmount(etSalaryPagibig) + getAmount(etSalaryTax) +
                getAmount(etSalaryOtherDeductions)
    }

    // -------------------------------------------------------------------------
    // Save / delete: always persist locally before starting Firebase sync
    // -------------------------------------------------------------------------

    private fun saveSalary() {
        if (isSaving) return
        val uid = userId
        if (uid.isNullOrBlank()) {
            redirectToLogin()
            return
        }

        val id = etSalaryId.text.toString().trim()
        val date = selectedSalaryDate
        val period = spSalaryPeriod.selectedItem?.toString().orEmpty()
        val gross = getAmount(etSalaryGross)
        val allowances = getAmount(etSalaryAllowances)
        val overtime = getAmount(etSalaryOvertime)
        val sss = getAmount(etSalarySSS)
        val philhealth = getAmount(etSalaryPhilhealth)
        val pagibig = getAmount(etSalaryPagibig)
        val tax = getAmount(etSalaryTax)
        val otherDeductions = getAmount(etSalaryOtherDeductions)
        val notes = etSalaryNotes.text.toString().trim()
        val earnings = gross + allowances + overtime
        val deductions = sss + philhealth + pagibig + tax + otherDeductions

        val error = when {
            date == null -> "Please select the salary payment date."
            gross <= 0.0 -> "Please enter a gross salary greater than zero."
            listOf(allowances, overtime, sss, philhealth, pagibig, tax, otherDeductions).any {
                !it.isFinite() || it < 0.0
            } -> "Earnings and deductions cannot be negative."
            !gross.isFinite() || !earnings.isFinite() || !deductions.isFinite() -> "Please enter valid salary amounts."
            deductions > earnings -> "Total deductions cannot exceed total earnings."
            period.isBlank() -> "Please select a salary period."
            else -> null
        }

        if (error != null) {
            tvSalaryFormError.text = error
            tvSalaryFormError.visibility = View.VISIBLE
            return
        }

        isSaving = true
        findViewById<View>(R.id.btnSaveSalary).isEnabled = false

        val recordId = if (id.isBlank()) UUID.randomUUID().toString() else id
        val record = SalaryRecord(
            id = recordId,
            date = date!!,
            period = period,
            gross = gross,
            allowances = allowances,
            overtime = overtime,
            sss = sss,
            philhealth = philhealth,
            pagibig = pagibig,
            tax = tax,
            otherDeductions = otherDeductions,
            notes = notes,
            updatedAt = System.currentTimeMillis()
        )

        val existingIndex = salaryRecords.indexOfFirst { it.id == recordId }
        if (existingIndex >= 0) salaryRecords[existingIndex] = record else salaryRecords.add(record)

        // Save record and pending operation in one SharedPreferences update.
        markPendingUpsert(record)
        salaryCalendar.timeInMillis = record.date
        salaryFormCard.visibility = View.GONE
        updateMonthDisplay()
        renderAll()
        isSaving = false
        findViewById<View>(R.id.btnSaveSalary).isEnabled = true

        showMessage("Salary saved on this device. Firebase sync is in progress.")
        syncPendingOperations()
    }

    private fun confirmDeleteSalary(id: String) {
        val record = salaryRecords.find { it.id == id } ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete Salary Record")
            .setMessage("Delete the salary record dated ${formatDate(record.date)}? This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                salaryRecords.removeAll { it.id == id }
                markPendingDelete(id)
                renderAll()
                showMessage("Salary deleted on this device. Firebase sync is in progress.")
                syncPendingOperations()
            }
            .show()
    }

    // -------------------------------------------------------------------------
    // Summary cards
    // -------------------------------------------------------------------------

    private fun updateSalaryStatistics() {
        val records = getSelectedMonthRecords()
        val gross = records.sumOf { it.gross }
        val allowances = records.sumOf { it.allowances }
        val overtime = records.sumOf { it.overtime }
        val deductions = records.sumOf { it.totalDeductions }
        val net = records.sumOf { it.netSalary }
        val averageNet = if (records.isNotEmpty()) net / records.size else 0.0

        findViewById<TextView>(R.id.tvSalaryGrossTotal).text = formatMoney(gross + allowances + overtime)
        findViewById<TextView>(R.id.tvSalaryDeductionsTotal).text = formatMoney(deductions)
        findViewById<TextView>(R.id.tvSalaryNetTotal).text = formatMoney(net)
        findViewById<TextView>(R.id.tvSalaryRecordCount).text = records.size.toString()
        findViewById<TextView>(R.id.tvSalaryAverageNet).text = formatMoney(averageNet)
        findViewById<TextView>(R.id.tvSalaryAllowancesTotal).text = formatMoney(allowances + overtime)
        findViewById<TextView>(R.id.tvSalaryMonthDescription).text =
            "Total take-home salary recorded in ${monthFormatter.format(salaryCalendar.time)}."

        updateDeductionSummary(records)
        updatePaydayBreakdown(records)
    }

    private fun updateDeductionSummary(records: List<SalaryRecord>) {
        val sss = records.sumOf { it.sss }
        val philhealth = records.sumOf { it.philhealth }
        val pagibig = records.sumOf { it.pagibig }
        val tax = records.sumOf { it.tax }
        val other = records.sumOf { it.otherDeductions }
        val total = sss + philhealth + pagibig + tax + other

        findViewById<TextView>(R.id.tvTotalSSS).text = formatMoney(sss)
        findViewById<TextView>(R.id.tvTotalPhilhealth).text = formatMoney(philhealth)
        findViewById<TextView>(R.id.tvTotalPagibig).text = formatMoney(pagibig)
        findViewById<TextView>(R.id.tvTotalSalaryTax).text = formatMoney(tax)
        findViewById<TextView>(R.id.tvTotalOtherDeductions).text = formatMoney(other)
        findViewById<TextView>(R.id.tvTotalPayrollDeductions).text = formatMoney(total)
    }

    private fun updatePaydayBreakdown(records: List<SalaryRecord>) {
        updatePaydayCard(
            records.filter { it.period == firstPeriod },
            R.id.tvFirstPaydayStatus,
            R.id.tvFirstPaydayDate,
            R.id.tvFirstPaydayNet,
            R.id.tvFirstPaydayDetails
        )
        updatePaydayCard(
            records.filter { it.period == secondPeriod },
            R.id.tvSecondPaydayStatus,
            R.id.tvSecondPaydayDate,
            R.id.tvSecondPaydayNet,
            R.id.tvSecondPaydayDetails
        )
    }

    private fun updatePaydayCard(
        records: List<SalaryRecord>,
        statusId: Int,
        dateId: Int,
        netId: Int,
        detailsId: Int
    ) {
        val totalNet = records.sumOf { it.netSalary }
        val totalGross = records.sumOf { it.totalEarnings }

        findViewById<TextView>(statusId).text = if (records.isEmpty()) "Not recorded" else "Received"
        findViewById<TextView>(dateId).text = if (records.isEmpty()) {
            "Payment date not recorded"
        } else {
            "Paid: " + records.sortedBy { it.date }.joinToString(", ") { formatDate(it.date) }
        }
        findViewById<TextView>(netId).text = formatMoney(totalNet)
        findViewById<TextView>(detailsId).text = if (records.isEmpty()) {
            "No salary records for this payday."
        } else {
            "${records.size} record(s) • Gross earnings: ${formatMoney(totalGross)}"
        }
    }

    private fun showPaydayDetails(period: String) {
        val records = getSelectedMonthRecords().filter { it.period == period }
        if (records.isEmpty()) {
            showMessage("No $period records for this month.")
            return
        }

        val details = records.sortedBy { it.date }.joinToString("\n\n") { record ->
            """Payment date: ${formatDate(record.date)}
Gross earnings: ${formatMoney(record.totalEarnings)}
Deductions: ${formatMoney(record.totalDeductions)}
Net salary: ${formatMoney(record.netSalary)}"""
        }

        AlertDialog.Builder(this)
            .setTitle(period)
            .setMessage(details)
            .setPositiveButton("Close", null)
            .show()
    }

    // -------------------------------------------------------------------------
    // Search and dynamic record cards
    // -------------------------------------------------------------------------

    private fun getFilteredSalaryRecords(): List<SalaryRecord> {
        val query = etSalarySearch.text.toString().trim().lowercase(Locale.ROOT)
        return getSelectedMonthRecords()
            .filter { record ->
                val searchable = listOf(
                    formatDate(record.date),
                    record.period,
                    record.notes,
                    record.gross.toString(),
                    record.netSalary.toString()
                ).joinToString(" ").lowercase(Locale.ROOT)
                searchable.contains(query)
            }
            .sortedByDescending { it.date }
    }

    private fun renderSalaryRecords() {
        val container = findViewById<LinearLayout>(R.id.salaryRecordsContainer)
        val emptyState = findViewById<View>(R.id.salaryEmptyState)
        val selectedRecords = getSelectedMonthRecords()
        val filtered = getFilteredSalaryRecords()

        container.removeAllViews()
        emptyState.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        container.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE

        findViewById<TextView>(R.id.tvSalaryCountLabel).text = "${filtered.size} records"
        findViewById<TextView>(R.id.tvSalaryRecordFilterSummary).text = when {
            selectedRecords.isEmpty() -> "No salary records for the selected month."
            filtered.isEmpty() -> "No salary records match your search."
            else -> "Showing ${filtered.size} of ${selectedRecords.size} salary records."
        }

        filtered.forEach { container.addView(createSalaryCard(it)) }
    }

    private fun createSalaryCard(record: SalaryRecord): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedBackground("#FFFFFF", "#E8EAF0")
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            makeText(record.period, 13f, "#6558D3", true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(makeText(formatDate(record.date), 10f, "#858B9E"))
        card.addView(header)

        card.addView(makeText("NET SALARY", 9f, "#858B9E", true).apply {
            setPadding(0, dp(14), 0, 0)
        })
        card.addView(makeText(formatMoney(record.netSalary), 24f, "#149B71", true).apply {
            setPadding(0, dp(4), 0, dp(12))
        })
        card.addView(createFinancialRow("Gross earnings", formatMoney(record.totalEarnings)))
        card.addView(createFinancialRow("Total deductions", formatMoney(record.totalDeductions)))
        card.addView(createFinancialRow(
            "SSS / PhilHealth / Pag-IBIG",
            formatMoney(record.sss + record.philhealth + record.pagibig)
        ))
        card.addView(createFinancialRow("Income tax", formatMoney(record.tax)))

        if (record.notes.isNotBlank()) {
            card.addView(makeText(record.notes, 10f, "#858B9E").apply {
                setPadding(0, dp(11), 0, dp(5))
            })
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(createAction("Edit", "#EFEDFF", "#6558D3") {
            showSalaryForm(record.id)
        }, actionLayoutParams())
        actions.addView(createAction("Delete", "#FFF0F0", "#D85D68") {
            confirmDeleteSalary(record.id)
        }, actionLayoutParams())
        card.addView(actions)

        return card.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(11) }
        }
    }

    private fun createFinancialRow(label: String, value: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))
        }
        row.addView(
            makeText(label, 10f, "#858B9E"),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(makeText(value, 11f, "#50556B", true))
        return row
    }

    // -------------------------------------------------------------------------
    // Rendering and status
    // -------------------------------------------------------------------------

    private fun renderAll() {
        findViewById<View>(R.id.salaryLoading).visibility = View.GONE
        updateSalaryStatistics()
        renderSalaryRecords()
    }

    private fun updateSyncStatus(message: String) {
        if (::tvSalarySyncStatus.isInitialized) tvSalarySyncStatus.text = message
    }

    private fun showDataError(message: String) {
        if (!::tvSalaryError.isInitialized) return
        tvSalaryError.text = message
        tvSalaryError.visibility = View.VISIBLE
    }

    private fun clearDataError() {
        if (::tvSalaryError.isInitialized) tvSalaryError.visibility = View.GONE
    }

    // -------------------------------------------------------------------------
    // UI helpers and formatting
    // -------------------------------------------------------------------------

    private fun makeText(value: String, size: Float, color: String, bold: Boolean = false): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(Color.parseColor(color))
            if (bold) setTypeface(null, Typeface.BOLD)
        }
    }

    private fun createAction(
        label: String,
        backgroundColor: String,
        textColor: String,
        action: () -> Unit
    ): TextView {
        return makeText(label, 10f, textColor, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(9), dp(16), dp(9))
            background = roundedBackground(backgroundColor, backgroundColor)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
    }

    private fun actionLayoutParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { marginEnd = dp(8) }

    private fun roundedBackground(color: String, strokeColor: String = color): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(color))
            cornerRadius = dp(11).toFloat()
            setStroke(dp(1), Color.parseColor(strokeColor))
        }
    }

    private fun getAmount(editText: EditText): Double {
        return parseAmount(editText.text.toString()) ?: 0.0
    }

    private fun parseAmount(value: Any?): Double? {
        val raw = when (value) {
            is Number -> value.toString()
            is String -> value.trim()
            else -> return null
        }
        if (raw.isBlank()) return null
        val accountingNegative = raw.startsWith("(") && raw.endsWith(")")
        val cleaned = raw.replace(",", "").replace(Regex("[^0-9.\\-]"), "")
        val parsed = cleaned.toDoubleOrNull() ?: return null
        if (!parsed.isFinite()) return null
        return if (accountingNegative && parsed > 0.0) -parsed else parsed
    }

    private fun formatMoney(amount: Double): String = currencyFormatter.format(amount)

    private fun formatDate(timestamp: Long): String = dateFormatter.format(Date(timestamp))

    private fun parseDate(value: String): Date? {
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd",
            "dd/MM/yyyy",
            "MM/dd/yyyy",
            "MMM d, yyyy",
            "MMMM d, yyyy"
        )
        for (pattern in patterns) {
            try {
                val formatter = SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    timeZone = manilaTimeZone
                }
                formatter.parse(value)?.let { return it }
            } catch (_: Exception) {
                // Try the next accepted format.
            }
        }
        return null
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

