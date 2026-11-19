package com.example.moneytracker

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
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

class CalendarActivity : AppCompatActivity() {

    // =====================================================
    // FIREBASE, ACCOUNT-SCOPED LOCAL CACHE
    // =====================================================

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var userId: String

    private val timezone: TimeZone = TimeZone.getTimeZone("Asia/Manila")
    private val cachePreferences by lazy {
        getSharedPreferences("calendar_cache_$userId", MODE_PRIVATE)
    }

    private var profileData: Map<String, Any?>? = null
    private val loadedData = mutableMapOf<String, List<CalendarDocument>>()
    private val pendingEventIds = mutableSetOf<String>()
    private var paydaySchedulePending = false
    private var loadGeneration = 0
    private var hasLoadedOnce = false
    private var isSavingPaydaySchedule = false

    private data class CalendarDocument(
        val id: String,
        val data: Map<String, Any?>
    )

    private val collectionNames = listOf(
        "calendarEvents",
        "salaries",
        "obligations",
        "payments",
        "expenses",
        "savings",
        "loans",
        "savingsGoals"
    )

    private data class CalendarEntry(
        val id: String,
        val date: Date,
        val title: String,
        val description: String,
        val type: String,
        val amount: Double = 0.0,
        val status: String = "",
        val source: String = ""
    )

    private val calendarEntries = mutableListOf<CalendarEntry>()

    // =====================================================
    // VIEW REFERENCES
    // =====================================================

    private lateinit var tvCalendarMonth: TextView
    private lateinit var tvSelectedCalendarDate: TextView
    private lateinit var calendarGridContainer: LinearLayout
    private lateinit var calendarDayDetailsContainer: LinearLayout
    private lateinit var calendarAgendaContainer: LinearLayout
    private lateinit var tvCalendarAgendaEmpty: TextView
    private lateinit var calendarAgendaViewContainer: LinearLayout
    private lateinit var tvCalendarAgendaViewEmpty: TextView

    private lateinit var tvNextPaydayName: TextView
    private lateinit var tvNextPaydayDate: TextView
    private lateinit var tvNextPaydayAmount: TextView
    private lateinit var tvPaydayCountdown: TextView

    private lateinit var tvForecastCashBalance: TextView
    private lateinit var tvForecastObligations: TextView
    private lateinit var tvForecastRemainingCash: TextView
    private lateinit var tvForecastExplanation: TextView

    private lateinit var etPaydayDayOne: EditText
    private lateinit var etPaydayAmountOne: EditText
    private lateinit var etPaydayDayTwo: EditText
    private lateinit var etPaydayAmountTwo: EditText
    private lateinit var btnSavePaydaySchedule: Button

    private lateinit var tvCalendarLoading: TextView
    private lateinit var tvCalendarError: TextView
    private lateinit var calendarProgress: ProgressBar

    // =====================================================
    // DATE AND PAYDAY STATE
    // =====================================================

    private var viewedMonth: Calendar = Calendar.getInstance(timezone)
    private var selectedDate: Date = Date()
    private var calendarMode: String = "month"

    // Defaults: 15th and last day of month (0 represents month-end).
    private var paydayDayOne = 15
    private var paydayDayTwo = 0
    private var paydayAmountOne = 0.0
    private var paydayAmountTwo = 0.0
    private var nextPaydayDate: Date? = null

    // =====================================================
    // COLORS
    // =====================================================

    private val purple = Color.rgb(101, 88, 211)
    private val purpleLight = Color.rgb(239, 237, 255)
    private val green = Color.rgb(20, 155, 113)
    private val greenLight = Color.rgb(231, 247, 240)
    private val red = Color.rgb(216, 93, 104)
    private val redLight = Color.rgb(255, 241, 241)
    private val orange = Color.rgb(213, 138, 54)
    private val orangeLight = Color.rgb(255, 245, 233)
    private val blue = Color.rgb(66, 133, 212)
    private val blueLight = Color.rgb(235, 243, 255)
    private val darkText = Color.rgb(32, 35, 52)
    private val mutedText = Color.rgb(133, 139, 158)
    private val borderColor = Color.rgb(235, 237, 243)

    // =====================================================
    // ACTIVITY LIFECYCLE
    // =====================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_calendar)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        initializeViews()
        setupWindowInsets()
        initializeCalendar()
        setupClickListeners()
        setupCalendarMode("month")
        checkUserSession()
    }

    override fun onResume() {
        super.onResume()
        // Refresh when returning from another screen, but avoid duplicating the initial request.
        if (hasLoadedOnce && ::userId.isInitialized && auth.currentUser?.uid == userId) {
            loadCalendarData()
        }
    }

    private fun initializeViews() {
        tvCalendarMonth = findViewById(R.id.tvCalendarMonth)
        tvSelectedCalendarDate = findViewById(R.id.tvSelectedCalendarDate)
        calendarGridContainer = findViewById(R.id.calendarGridContainer)
        calendarDayDetailsContainer = findViewById(R.id.calendarDayDetailsContainer)
        calendarAgendaContainer = findViewById(R.id.calendarAgendaContainer)
        tvCalendarAgendaEmpty = findViewById(R.id.tvCalendarAgendaEmpty)
        calendarAgendaViewContainer = findViewById(R.id.calendarAgendaViewContainer)
        tvCalendarAgendaViewEmpty = findViewById(R.id.tvCalendarAgendaViewEmpty)

        tvNextPaydayName = findViewById(R.id.tvNextPaydayName)
        tvNextPaydayDate = findViewById(R.id.tvNextPaydayDate)
        tvNextPaydayAmount = findViewById(R.id.tvNextPaydayAmount)
        tvPaydayCountdown = findViewById(R.id.tvPaydayCountdown)

        tvForecastCashBalance = findViewById(R.id.tvForecastCashBalance)
        tvForecastObligations = findViewById(R.id.tvForecastObligations)
        tvForecastRemainingCash = findViewById(R.id.tvForecastRemainingCash)
        tvForecastExplanation = findViewById(R.id.tvForecastExplanation)

        etPaydayDayOne = findViewById(R.id.etPaydayDayOne)
        etPaydayAmountOne = findViewById(R.id.etPaydayAmountOne)
        etPaydayDayTwo = findViewById(R.id.etPaydayDayTwo)
        etPaydayAmountTwo = findViewById(R.id.etPaydayAmountTwo)
        btnSavePaydaySchedule = findViewById(R.id.btnSavePaydaySchedule)

        tvCalendarLoading = findViewById(R.id.tvCalendarLoading)
        tvCalendarError = findViewById(R.id.tvCalendarError)
        calendarProgress = findViewById(R.id.calendarProgress)
    }

    private fun setupWindowInsets() {
        val rootView = findViewById<View>(R.id.main)
        val initialLeft = rootView.paddingLeft
        val initialTop = rootView.paddingTop
        val initialRight = rootView.paddingRight
        val initialBottom = rootView.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                initialLeft + systemBars.left,
                initialTop + systemBars.top,
                initialRight + systemBars.right,
                initialBottom + systemBars.bottom
            )
            insets
        }
    }

    private fun initializeCalendar() {
        val now = Calendar.getInstance(timezone)
        selectedDate = now.time
        viewedMonth = (now.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        updateMonthLabel()
    }

    private fun setupClickListeners() {
        findViewById<View>(R.id.btnCalendarBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnAddCalendarEvent).setOnClickListener { showAddEventDialog() }

        findViewById<View>(R.id.btnCalendarToday).setOnClickListener {
            selectedDate = startOfDay(Date())
            viewedMonth = getCalendar(selectedDate).apply { set(Calendar.DAY_OF_MONTH, 1) }
            updateMonthLabel()
            renderCalendar()
        }

        findViewById<View>(R.id.btnPreviousMonth).setOnClickListener { changeMonth(-1) }
        findViewById<View>(R.id.btnNextMonth).setOnClickListener { changeMonth(1) }
        findViewById<View>(R.id.btnCalendarMonthView).setOnClickListener { setupCalendarMode("month") }
        findViewById<View>(R.id.btnCalendarWeekView).setOnClickListener { setupCalendarMode("week") }
        findViewById<View>(R.id.btnCalendarAgendaView).setOnClickListener { setupCalendarMode("agenda") }
        findViewById<View>(R.id.btnViewAllEvents).setOnClickListener {
            setupCalendarMode("agenda")
            findViewById<ScrollView>(R.id.calendarScroll).post {
                findViewById<ScrollView>(R.id.calendarScroll)
                    .smoothScrollTo(0, calendarAgendaViewContainer.top)
            }
        }

        btnSavePaydaySchedule.setOnClickListener { savePaydaySchedule() }
    }

    // =====================================================
    // AUTHENTICATION
    // =====================================================

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
        loadCalendarData()
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

    // =====================================================
    // LOCAL CACHE: LOAD / SAVE
    // =====================================================

    private fun loadLocalCache(): Boolean {
        return try {
            val raw = cachePreferences.getString("calendar_snapshot", null) ?: return false
            val root = JSONObject(raw)
            profileData = root.optJSONObject("profile")?.let { jsonObjectToMap(it) }

            loadedData.clear()
            val collections = root.optJSONObject("collections") ?: JSONObject()
            collectionNames.forEach { collection ->
                val array = collections.optJSONArray(collection) ?: JSONArray()
                val docs = mutableListOf<CalendarDocument>()
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val data = item.optJSONObject("data")?.let { jsonObjectToMap(it) } ?: emptyMap<String, Any?>()
                    docs.add(CalendarDocument(id, data))
                }
                loadedData[collection] = docs
            }

            pendingEventIds.clear()
            val pending = root.optJSONArray("pendingEventIds") ?: JSONArray()
            for (i in 0 until pending.length()) pending.optString(i).takeIf { it.isNotBlank() }?.let(pendingEventIds::add)
            paydaySchedulePending = root.optBoolean("paydaySchedulePending", false)
            true
        } catch (exception: Exception) {
            android.util.Log.e("CalendarActivity", "Unable to read local calendar cache", exception)
            false
        }
    }

    private fun saveLocalCache() {
        try {
            val root = JSONObject()
            root.put("profile", profileData?.let { mapToJsonObject(it) } ?: JSONObject())

            val collections = JSONObject()
            collectionNames.forEach { collection ->
                val array = JSONArray()
                loadedData[collection].orEmpty().forEach { doc ->
                    array.put(JSONObject().apply {
                        put("id", doc.id)
                        put("data", mapToJsonObject(doc.data))
                    })
                }
                collections.put(collection, array)
            }
            root.put("collections", collections)
            root.put("pendingEventIds", JSONArray().apply { pendingEventIds.forEach { eventId -> put(eventId) } })
            root.put("paydaySchedulePending", paydaySchedulePending)

            cachePreferences.edit().putString("calendar_snapshot", root.toString()).apply()
        } catch (exception: Exception) {
            android.util.Log.e("CalendarActivity", "Unable to save local calendar cache", exception)
        }
    }

    private fun mapToJsonObject(map: Map<String, Any?>): JSONObject {
        val result = JSONObject()
        map.forEach { (key, value) -> result.put(key, encodeCacheValue(value)) }
        return result
    }

    private fun encodeCacheValue(value: Any?): Any? = when (value) {
        null -> JSONObject.NULL
        is Timestamp -> JSONObject().put("__calendar_cache_type", "timestamp").put("value", value.toDate().time)
        is Date -> JSONObject().put("__calendar_cache_type", "date").put("value", value.time)
        is Map<*, *> -> {
            val converted = JSONObject()
            value.forEach { (key, nested) -> if (key is String) converted.put(key, encodeCacheValue(nested)) }
            converted
        }
        is Iterable<*> -> JSONArray().apply { value.forEach { put(encodeCacheValue(it)) } }
        is Array<*> -> JSONArray().apply { value.forEach { put(encodeCacheValue(it)) } }
        is String, is Number, is Boolean, is JSONObject, is JSONArray -> value
        else -> value.toString()
    }

    private fun jsonObjectToMap(json: JSONObject): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = decodeCacheValue(json.opt(key))
        }
        return result
    }

    private fun decodeCacheValue(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> {
            val marker = value.optString("__calendar_cache_type")
            if (marker == "timestamp") Timestamp(Date(value.optLong("value")))
            else if (marker == "date") Date(value.optLong("value"))
            else jsonObjectToMap(value)
        }
        is JSONArray -> (0 until value.length()).map { decodeCacheValue(value.opt(it)) }
        else -> value
    }

    // =====================================================
    // LOAD LOCAL FIRST, THEN REFRESH FIRESTORE
    // =====================================================

    private fun loadCalendarData() {
        loadGeneration++
        val generation = loadGeneration
        clearError()

        val hasCache = loadLocalCache()
        if (profileData != null) loadPaydaySettings(profileData!!)
        else setDefaultPaydaySettings()

        if (hasCache) {
            buildCalendarEntries()
            renderCalendar()
            updateNextPayday()
            updateFinancialForecast()
            renderUpcomingActivities()
            setLoading(false)
        } else {
            setLoading(true)
        }

        val userRef = db.collection("users").document(userId)
        var completed = 0
        var failed = 0
        val totalRequests = collectionNames.size + 1

        fun requestFinished() {
            if (generation != loadGeneration) return
            completed++
            if (completed != totalRequests) return

            saveLocalCache()
            setLoading(false)
            buildCalendarEntries()
            updateMonthLabel()
            renderCalendar()
            updateNextPayday()
            updateFinancialForecast()
            renderUpcomingActivities()
            syncPendingCalendarEvents()
            syncPendingPaydaySchedule()
            hasLoadedOnce = true

            if (failed > 0) {
                showError("$failed data request(s) could not be refreshed. Showing available saved data. Check your connection and Firestore rules.")
            } else {
                clearError()
            }
        }

        userRef.get()
            .addOnSuccessListener { snapshot ->
                if (generation == loadGeneration) {
                    val refreshedProfile = mutableMapOf<String, Any?>().apply {
                        snapshot.data?.let { putAll(it) }
                    }
                    // A locally saved schedule must not be overwritten by an older cloud copy
                    // while its write is still pending.
                    if (paydaySchedulePending && profileData != null) {
                        listOf("paydayDayOne", "paydayDayTwo", "paydayAmountOne", "paydayAmountTwo").forEach { key ->
                            if (profileData!!.containsKey(key)) refreshedProfile[key] = profileData!![key]
                        }
                    }
                    profileData = refreshedProfile
                    loadPaydaySettings(profileData!!)
                }
                requestFinished()
            }
            .addOnFailureListener { exception ->
                android.util.Log.w("CalendarActivity", "Profile refresh failed; using cache", exception)
                if (profileData == null) setDefaultPaydaySettings()
                failed++
                requestFinished()
            }

        collectionNames.forEach { collectionName ->
            userRef.collection(collectionName).get()
                .addOnSuccessListener { snapshot ->
                    if (generation == loadGeneration) {
                        val refreshedDocuments = snapshot.documents.mapNotNull { document ->
                            document.data?.let { CalendarDocument(document.id, HashMap(it)) }
                        }.toMutableList()

                        // Keep locally created events until their pending cloud write succeeds.
                        if (collectionName == "calendarEvents" && pendingEventIds.isNotEmpty()) {
                            val remoteIds = refreshedDocuments.map { it.id }.toSet()
                            loadedData[collectionName].orEmpty()
                                .filter { it.id in pendingEventIds && it.id !in remoteIds }
                                .forEach { pendingDocument -> refreshedDocuments.add(pendingDocument) }
                        }
                        loadedData[collectionName] = refreshedDocuments
                    }
                    requestFinished()
                }
                .addOnFailureListener { exception ->
                    android.util.Log.w("CalendarActivity", "Collection refresh failed: $collectionName", exception)
                    failed++
                    requestFinished()
                }
        }
    }

    // =====================================================
    // PAYDAY SETTINGS: LOCAL-FIRST + FIRESTORE
    // =====================================================

    private fun loadPaydaySettings(profile: Map<String, Any?>) {
        val savedDayOne = intFrom(profile, "paydayDayOne")
        val savedDayTwo = intFrom(profile, "paydayDayTwo")

        paydayDayOne = if (savedDayOne in 1..31) savedDayOne else 15
        paydayDayTwo = if (savedDayTwo in 0..31) savedDayTwo else 0
        if (paydayDayOne == paydayDayTwo) paydayDayTwo = 0

        paydayAmountOne = amountFrom(profile, "paydayAmountOne").coerceAtLeast(0.0)
        paydayAmountTwo = amountFrom(profile, "paydayAmountTwo").coerceAtLeast(0.0)
        showPaydaySettingsInForm()
    }

    private fun setDefaultPaydaySettings() {
        paydayDayOne = 15
        paydayDayTwo = 0
        paydayAmountOne = 0.0
        paydayAmountTwo = 0.0
        showPaydaySettingsInForm()
    }

    private fun showPaydaySettingsInForm() {
        etPaydayDayOne.setText(paydayDayOne.toString())
        etPaydayDayTwo.setText(paydayDayTwo.toString())
        etPaydayAmountOne.setText(if (paydayAmountOne > 0.0) paydayAmountOne.toString() else "")
        etPaydayAmountTwo.setText(if (paydayAmountTwo > 0.0) paydayAmountTwo.toString() else "")
    }

    private fun savePaydaySchedule() {
        if (isSavingPaydaySchedule) return

        val dayOne = etPaydayDayOne.text.toString().trim().toIntOrNull()
        val dayTwo = etPaydayDayTwo.text.toString().trim().toIntOrNull()
        val amountOne = parseAmount(etPaydayAmountOne.text.toString())
        val amountTwo = parseAmount(etPaydayAmountTwo.text.toString())

        if (dayOne == null || dayOne !in 1..31) {
            etPaydayDayOne.error = "Enter a day from 1 to 31"
            etPaydayDayOne.requestFocus()
            return
        }
        if (dayTwo == null || dayTwo !in 0..31) {
            etPaydayDayTwo.error = "Enter 0 for month-end or a day from 1 to 31"
            etPaydayDayTwo.requestFocus()
            return
        }
        if (dayTwo != 0 && dayOne == dayTwo) {
            showError("Your two payday dates cannot be the same.")
            return
        }
        if (paydayDatesCollide(dayOne, dayTwo)) {
            showError("These payday settings fall on the same calendar day in at least one month. Choose two dates that stay separate throughout the year.")
            return
        }
        if (amountOne < 0.0 || amountTwo < 0.0 || !amountOne.isFinite() || !amountTwo.isFinite()) {
            showError("Payday amounts must be valid non-negative numbers.")
            return
        }

        isSavingPaydaySchedule = true
        btnSavePaydaySchedule.isEnabled = false
        btnSavePaydaySchedule.text = "Saving..."
        hideKeyboard()

        paydayDayOne = dayOne
        paydayDayTwo = dayTwo
        paydayAmountOne = amountOne
        paydayAmountTwo = amountTwo

        val updatedProfile = (profileData ?: emptyMap()).toMutableMap().apply {
            put("paydayDayOne", dayOne)
            put("paydayAmountOne", amountOne)
            put("paydayDayTwo", dayTwo)
            put("paydayAmountTwo", amountTwo)
        }
        profileData = updatedProfile
        paydaySchedulePending = true
        saveLocalCache()
        renderCalendar()
        updateNextPayday()
        updateFinancialForecast()
        finishPaydaySave()
        showToast("Payday schedule saved on this device. Cloud sync will complete when connected.")

        val schedule = hashMapOf<String, Any>(
            "paydayDayOne" to dayOne,
            "paydayAmountOne" to amountOne,
            "paydayDayTwo" to dayTwo,
            "paydayAmountTwo" to amountTwo,
            "updatedAt" to FieldValue.serverTimestamp()
        )

        db.collection("users").document(userId)
            .set(schedule, SetOptions.merge())
            .addOnSuccessListener {
                paydaySchedulePending = false
                saveLocalCache()
                clearError()
                showToast("Payday schedule synced to your account.")
            }
            .addOnFailureListener { exception ->
                // Keep the new values in local storage; retry on the next refresh.
                paydaySchedulePending = true
                saveLocalCache()
                showError("Payday schedule remains saved on this device. Cloud sync is pending: ${exception.localizedMessage.orEmpty()}")
            }
    }

    private fun finishPaydaySave() {
        isSavingPaydaySchedule = false
        btnSavePaydaySchedule.isEnabled = true
        btnSavePaydaySchedule.text = "Save Payday Schedule"
    }

    private fun syncPendingPaydaySchedule() {
        if (!paydaySchedulePending) return
        val profile = profileData ?: return
        val dayOne = intFrom(profile, "paydayDayOne").takeIf { it in 1..31 } ?: paydayDayOne
        val dayTwo = intFrom(profile, "paydayDayTwo").takeIf { it in 0..31 } ?: paydayDayTwo
        val amountOne = amountFrom(profile, "paydayAmountOne")
        val amountTwo = amountFrom(profile, "paydayAmountTwo")
        val updates = hashMapOf<String, Any>(
            "paydayDayOne" to dayOne,
            "paydayDayTwo" to dayTwo,
            "paydayAmountOne" to amountOne,
            "paydayAmountTwo" to amountTwo,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        db.collection("users").document(userId).set(updates, SetOptions.merge())
            .addOnSuccessListener {
                paydaySchedulePending = false
                saveLocalCache()
            }
            .addOnFailureListener { exception ->
                android.util.Log.w("CalendarActivity", "Payday schedule sync still pending", exception)
            }
    }

    // =====================================================
    // CALENDAR MODES AND NAVIGATION
    // =====================================================

    private fun setupCalendarMode(mode: String) {
        calendarMode = mode
        setModeButton(findViewById(R.id.btnCalendarMonthView), mode == "month")
        setModeButton(findViewById(R.id.btnCalendarWeekView), mode == "week")
        setModeButton(findViewById(R.id.btnCalendarAgendaView), mode == "agenda")

        val grid = findViewById<View>(R.id.calendarMonthContainer)
        grid.visibility = if (mode == "agenda") View.GONE else View.VISIBLE
        calendarAgendaViewContainer.visibility = if (mode == "agenda") View.VISIBLE else View.GONE
        renderCalendar()
    }

    private fun setModeButton(button: TextView, selected: Boolean) {
        button.setTextColor(if (selected) Color.WHITE else purple)
        button.background = roundedBackground(
            if (selected) purple else purpleLight,
            if (selected) purple else purpleLight,
            9
        )
    }

    private fun changeMonth(direction: Int) {
        val previousDay = getCalendar(selectedDate).get(Calendar.DAY_OF_MONTH)
        viewedMonth.add(Calendar.MONTH, direction)
        viewedMonth.set(Calendar.DAY_OF_MONTH, 1)
        val lastDay = viewedMonth.getActualMaximum(Calendar.DAY_OF_MONTH)
        val selected = getCalendar(viewedMonth.time)
        selected.set(Calendar.DAY_OF_MONTH, previousDay.coerceAtMost(lastDay))
        selectedDate = selected.time
        updateMonthLabel()
        renderCalendar()
    }

    private fun updateMonthLabel() {
        tvCalendarMonth.text = SimpleDateFormat("MMMM yyyy", Locale.US).apply {
            timeZone = timezone
        }.format(viewedMonth.time)
    }

    // =====================================================
    // CALENDAR RENDERING
    // =====================================================

    private fun renderCalendar() {
        when (calendarMode) {
            "month" -> renderMonthView()
            "week" -> renderWeekView()
            "agenda" -> renderAgendaView()
            else -> renderMonthView()
        }
        updateSelectedDateDetails()
        renderUpcomingActivities()
    }

    private fun renderMonthView() {
        calendarGridContainer.removeAllViews()
        calendarGridContainer.orientation = LinearLayout.VERTICAL
        addWeekdayHeader()

        val firstDay = getCalendar(viewedMonth.time).apply { set(Calendar.DAY_OF_MONTH, 1) }
        val offset = firstDay.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY
        val totalDays = firstDay.getActualMaximum(Calendar.DAY_OF_MONTH)
        val rows = (offset + totalDays + 6) / 7
        var day = 1

        for (rowIndex in 0 until rows) {
            val row = createCalendarRow()
            for (column in 0 until 7) {
                val cellIndex = rowIndex * 7 + column
                if (cellIndex < offset || day > totalDays) row.addView(createEmptyDayCell())
                else {
                    row.addView(createDayCell(dateInViewedMonth(day)))
                    day++
                }
            }
            calendarGridContainer.addView(row)
        }
    }

    private fun renderWeekView() {
        calendarGridContainer.removeAllViews()
        calendarGridContainer.orientation = LinearLayout.VERTICAL
        addWeekdayHeader()
        val start = getCalendar(selectedDate).apply {
            set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val row = createCalendarRow()
        repeat(7) {
            row.addView(createDayCell(start.time))
            start.add(Calendar.DAY_OF_MONTH, 1)
        }
        calendarGridContainer.addView(row)
    }

    private fun addWeekdayHeader() {
        val header = createCalendarRow()
        listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat").forEach { name ->
            header.addView(TextView(this).apply {
                text = name
                gravity = Gravity.CENTER
                setTextColor(mutedText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, dp(35), 1f)
            })
        }
        calendarGridContainer.addView(header)
    }

    private fun createCalendarRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun createEmptyDayCell(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, dp(53), 1f).apply {
            setMargins(dp(2), dp(2), dp(2), dp(2))
        }
    }

    private fun createDayCell(date: Date): View {
        val day = getCalendar(date).get(Calendar.DAY_OF_MONTH)
        val events = getEntriesForDate(date)
        val isSelected = sameDay(date, selectedDate)
        val isToday = sameDay(date, Date())
        val backgroundColor = when {
            isSelected -> purple
            isToday -> greenLight
            events.isNotEmpty() -> purpleLight
            else -> Color.WHITE
        }
        val textColor = when {
            isSelected -> Color.WHITE
            isToday -> green
            else -> darkText
        }

        return TextView(this).apply {
            text = if (events.isNotEmpty()) "$day\n•" else day.toString()
            gravity = Gravity.CENTER
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = if (isSelected || isToday) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            background = roundedBackground(
                backgroundColor,
                if (isToday && !isSelected) green else backgroundColor,
                10
            )
            isClickable = true
            isFocusable = true
            contentDescription = "${formatDate(date)}, ${events.size} event(s)"
            layoutParams = LinearLayout.LayoutParams(0, dp(53), 1f).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }
            setOnClickListener {
                selectedDate = date
                if (calendarMode == "month" || calendarMode == "week") renderCalendar()
            }
        }
    }

    // =====================================================
    // AGENDA + UPCOMING ACTIVITIES + SELECTED DATE
    // =====================================================

    private fun renderAgendaView() {
        calendarAgendaViewContainer.removeAllViews()
        calendarAgendaViewContainer.addView(tvCalendarAgendaViewEmpty)
        val entries = getEntriesForViewedMonth().sortedBy { it.date }
        tvCalendarAgendaViewEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        if (entries.isEmpty()) return

        var previousKey = ""
        entries.forEach { entry ->
            val key = dateKey(entry.date)
            if (key != previousKey) {
                previousKey = key
                calendarAgendaViewContainer.addView(createDateHeading(entry.date))
            }
            calendarAgendaViewContainer.addView(createEventRow(entry))
        }
    }

    private fun createDateHeading(date: Date): TextView = TextView(this).apply {
        text = formatFullDate(date)
        setTextColor(darkText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(5), dp(14), dp(5), dp(6))
    }

    private fun updateSelectedDateDetails() {
        tvSelectedCalendarDate.text = formatFullDate(selectedDate)
        calendarDayDetailsContainer.removeAllViews()
        val entries = getEntriesForDate(selectedDate).sortedBy { it.date }
        if (entries.isEmpty()) {
            calendarDayDetailsContainer.addView(TextView(this).apply {
                text = "No events scheduled for this date."
                setTextColor(mutedText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(18), dp(10), dp(18))
            })
            return
        }
        entries.forEach { calendarDayDetailsContainer.addView(createEventRow(it)) }
    }

    private fun renderUpcomingActivities() {
        if (!::calendarAgendaContainer.isInitialized) return
        calendarAgendaContainer.removeAllViews()
        calendarAgendaContainer.addView(tvCalendarAgendaEmpty)
        val start = startOfDay(Date())
        val endCalendar = getCalendar(start).apply { add(Calendar.MONTH, 6) }
        val end = endOfDay(endCalendar.time)
        val actual = calendarEntries.filter { !it.date.before(start) && !it.date.after(end) }
        val scheduled = generatePaydays(start, end)
        val entries = (actual + scheduled)
            .distinctBy { "${it.source}:${it.id}" }
            .sortedBy { it.date }
            .take(8)

        tvCalendarAgendaEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        if (entries.isNotEmpty()) {
            entries.forEach { calendarAgendaContainer.addView(createEventRow(it)) }
        }
    }

    private fun createEventRow(entry: CalendarEntry): View {
        val accent = colorForEvent(entry.type)
        val light = lightColorForEvent(entry.type)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBackground(Color.WHITE, borderColor, 12)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(7) }
        }

        val icon = TextView(this).apply {
            text = symbolForEvent(entry.type)
            gravity = Gravity.CENTER
            setTextColor(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            background = roundedBackground(light, light, 10)
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
        }

        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
            }
        }
        details.addView(TextView(this).apply {
            text = entry.title
            setTextColor(darkText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
        })
        details.addView(TextView(this).apply {
            val statusText = entry.status.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            text = entry.description + statusText
            setTextColor(mutedText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            maxLines = 2
        })
        row.addView(icon)
        row.addView(details)

        if (entry.amount > 0.0) {
            row.addView(TextView(this).apply {
                text = formatCurrency(entry.amount)
                setTextColor(accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.END
                maxLines = 1
            })
        }
        return row
    }

    // =====================================================
    // EVENT COLORS AND SYMBOLS
    // =====================================================

    private fun colorForEvent(type: String): Int = when (type.lowercase(Locale.ROOT)) {
        "salary", "payday", "income" -> green
        "expense" -> orange
        "bill", "obligation", "loan due", "loan" -> red
        "payment" -> orange
        "savings", "savings contribution", "savings withdrawal" -> blue
        "goal" -> purple
        else -> purple
    }

    private fun lightColorForEvent(type: String): Int = when (type.lowercase(Locale.ROOT)) {
        "salary", "payday", "income" -> greenLight
        "expense", "payment" -> orangeLight
        "bill", "obligation", "loan due", "loan" -> redLight
        "savings", "savings contribution", "savings withdrawal" -> blueLight
        else -> purpleLight
    }

    private fun symbolForEvent(type: String): String = when (type.lowercase(Locale.ROOT)) {
        "salary", "payday", "income" -> "₱"
        "expense" -> "−"
        "bill", "obligation" -> "!"
        "loan due", "loan" -> "L"
        "payment" -> "✓"
        "savings", "savings contribution" -> "+"
        "savings withdrawal" -> "−"
        "goal" -> "★"
        else -> "•"
    }

    // =====================================================
    // BUILD CALENDAR ENTRIES FROM NORMALIZED DATA
    // =====================================================

    private fun buildCalendarEntries() {
        calendarEntries.clear()

        // User-created calendar events.
        loadedData["calendarEvents"].orEmpty().forEach { doc ->
            val date = documentDate(doc.data, "eventDate", "date", "startDate", "dateString") ?: return@forEach
            calendarEntries.add(CalendarEntry(
                id = doc.id,
                date = date,
                title = stringFrom(doc.data, "title", "name").ifBlank { "Calendar event" },
                description = stringFrom(doc.data, "description", "notes"),
                type = stringFrom(doc.data, "type").ifBlank { "Custom" },
                amount = amountFrom(doc.data, "amount"),
                status = stringFrom(doc.data, "status"),
                source = "calendarEvents"
            ))
        }

        // Salary payments.
        loadedData["salaries"].orEmpty().forEach { doc ->
            val date = documentDate(doc.data, "salaryDate", "paymentDate", "date", "createdAt") ?: return@forEach
            val period = stringFrom(doc.data, "salaryPeriod", "paydayPeriodLabel", "paydayPeriod", "period")
            val title = when {
                period.contains("15", ignoreCase = true) -> "15th Salary Received"
                period.contains("month", ignoreCase = true) -> "Month-end Salary Received"
                else -> "Salary Received"
            }
            calendarEntries.add(CalendarEntry(
                id = doc.id,
                date = date,
                title = title,
                description = "Recorded salary payment",
                type = "Salary",
                amount = amountFrom(doc.data, "netSalary", "netAmount", "netPay", "takeHomePay", "amount"),
                status = "Received",
                source = "salaries"
            ))
        }

        // Expenses.
        loadedData["expenses"].orEmpty().forEach { doc ->
            val date = documentDate(doc.data, "date", "expenseDate", "transactionDate", "createdAt", "dateString") ?: return@forEach
            val category = stringFrom(doc.data, "category").ifBlank { "Expense" }
            calendarEntries.add(CalendarEntry(
                id = doc.id,
                date = date,
                title = stringFrom(doc.data, "description", "name").ifBlank { "Expense" },
                description = "Expense · $category",
                type = "Expense",
                amount = amountFrom(doc.data, "amount", "total"),
                status = "Recorded",
                source = "expenses"
            ))
        }

        // Bill and loan obligations.
        loadedData["obligations"].orEmpty().forEach { doc ->
            val date = documentDate(doc.data, "dueDate", "date", "paymentDate") ?: return@forEach
            val status = stringFrom(doc.data, "status")
            val type = stringFrom(doc.data, "type", "obligationType").ifBlank { "Bill" }
            val paid = isPaidStatus(status)
            val remaining = if (paid) 0.0 else remainingObligation(doc.data)
            calendarEntries.add(CalendarEntry(
                id = doc.id,
                date = date,
                title = stringFrom(doc.data, "description", "name", "payee").ifBlank { "$type Due" },
                description = "$type due date",
                type = if (type.contains("loan", true)) "Loan Due" else "Obligation",
                amount = remaining,
                status = status.ifBlank { if (paid) "Paid" else "Unpaid" },
                source = "obligations"
            ))
        }

        // Loans with their own next payment or due date.
        loadedData["loans"].orEmpty().forEach { doc ->
            val date = documentDate(doc.data, "nextPaymentDate", "dueDate", "paymentDueDate") ?: return@forEach
            val status = stringFrom(doc.data, "status")
            if (status.equals("cancelled", true) || status.equals("closed", true) || status.equals("paid", true)) return@forEach
            calendarEntries.add(CalendarEntry(
                id = doc.id,
                date = date,
                title = stringFrom(doc.data, "name", "loanName", "lender").ifBlank { "Loan payment due" },
                description = "Loan due date",
                type = "Loan Due",
                amount = amountFrom(doc.data, "nextPaymentAmount", "monthlyPayment", "outstandingBalance", "remainingBalance"),
                status = status,
                source = "loans"
            ))
        }

        // Recorded payments.
        loadedData["payments"].orEmpty().forEach { doc ->
            val date = documentDate(doc.data, "paymentDate", "date", "transactionDate", "createdAt") ?: return@forEach
            calendarEntries.add(CalendarEntry(
                id = doc.id,
                date = date,
                title = stringFrom(doc.data, "description", "payee", "obligationName", "loanName").ifBlank { "Payment recorded" },
                description = stringFrom(doc.data, "type", "paymentType", "category").ifBlank { "Payment" },
                type = "Payment",
                amount = amountFrom(doc.data, "amount", "paymentAmount", "paidAmount"),
                status = stringFrom(doc.data, "status").ifBlank { "Paid" },
                source = "payments"
            ))
        }

        // Savings movements.
        loadedData["savings"].orEmpty().forEach { doc ->
            val date = documentDate(doc.data, "date", "transactionDate", "createdAt") ?: return@forEach
            val movement = stringFrom(doc.data, "transactionType", "type", "movementType").lowercase(Locale.ROOT)
            val withdrawal = movement.contains("withdraw")
            calendarEntries.add(CalendarEntry(
                id = doc.id,
                date = date,
                title = if (withdrawal) "Savings withdrawal" else "Savings contribution",
                description = stringFrom(doc.data, "accountName", "account", "description").ifBlank { "Savings movement" },
                type = if (withdrawal) "Savings Withdrawal" else "Savings Contribution",
                amount = amountFrom(doc.data, "amount", "transactionAmount"),
                status = "Recorded",
                source = "savings"
            ))
        }

        // Savings goal target dates.
        loadedData["savingsGoals"].orEmpty().forEach { doc ->
            val date = documentDate(doc.data, "targetDate", "dueDate") ?: return@forEach
            calendarEntries.add(CalendarEntry(
                id = doc.id,
                date = date,
                title = stringFrom(doc.data, "name", "goalName", "title").ifBlank { "Savings Goal Target" },
                description = "Savings goal target date",
                type = "Goal",
                amount = amountFrom(doc.data, "targetAmount", "target"),
                status = stringFrom(doc.data, "status"),
                source = "savingsGoals"
            ))
        }

        calendarEntries.sortBy { it.date }
    }

    // =====================================================
    // GENERATED RECURRING PAYDAYS
    // =====================================================

    private fun generatePaydays(startDate: Date, endDate: Date): List<CalendarEntry> {
        val result = mutableListOf<CalendarEntry>()
        val monthCursor = getCalendar(startDate).apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val finalMonth = getCalendar(endDate).apply { set(Calendar.DAY_OF_MONTH, 1) }
        var iterations = 0

        while (!monthCursor.after(finalMonth) && iterations < 36) {
            val year = monthCursor.get(Calendar.YEAR)
            val month = monthCursor.get(Calendar.MONTH)
            val firstDate = paydayDate(year, month, paydayDayOne)
            val secondDate = paydayDate(year, month, paydayDayTwo)

            if (dateIsInside(firstDate, startDate, endDate) && !hasSalaryOnDate(firstDate)) {
                result.add(CalendarEntry(
                    id = "scheduled_first_${dateKey(firstDate)}",
                    date = firstDate,
                    title = "Payday · Day $paydayDayOne",
                    description = "Scheduled first payday",
                    type = "Payday",
                    amount = paydayAmountOne,
                    status = "Scheduled",
                    source = "paydaySchedule"
                ))
            }

            if (!sameDay(firstDate, secondDate) && dateIsInside(secondDate, startDate, endDate) && !hasSalaryOnDate(secondDate)) {
                result.add(CalendarEntry(
                    id = "scheduled_second_${dateKey(secondDate)}",
                    date = secondDate,
                    title = if (paydayDayTwo == 0) "Month-end Payday" else "Payday · Day $paydayDayTwo",
                    description = "Scheduled second payday",
                    type = "Payday",
                    amount = paydayAmountTwo,
                    status = "Scheduled",
                    source = "paydaySchedule"
                ))
            }
            monthCursor.add(Calendar.MONTH, 1)
            iterations++
        }
        return result
    }

    private fun dateIsInside(date: Date, start: Date, end: Date): Boolean =
        !date.before(startOfDay(start)) && !date.after(endOfDay(end))

    private fun hasSalaryOnDate(date: Date): Boolean = calendarEntries.any {
        it.type.equals("Salary", ignoreCase = true) && sameDay(it.date, date)
    }

    private fun paydayDatesCollide(dayOne: Int, dayTwo: Int): Boolean {
        return (0..11).any { monthOffset ->
            val calendar = getCalendar(Date()).apply { add(Calendar.MONTH, monthOffset) }
            sameDay(
                paydayDate(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), dayOne),
                paydayDate(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), dayTwo)
            )
        }
    }

    private fun paydayDate(year: Int, month: Int, requestedDay: Int): Date {
        val calendar = Calendar.getInstance(timezone)
        calendar.clear()
        calendar.set(year, month, 1, 12, 0, 0)
        val lastDay = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
        val actualDay = if (requestedDay == 0) lastDay else requestedDay.coerceIn(1, lastDay)
        calendar.set(Calendar.DAY_OF_MONTH, actualDay)
        return calendar.time
    }

    private fun getEntriesForDate(date: Date): List<CalendarEntry> {
        val actual = calendarEntries.filter { sameDay(it.date, date) }
        val scheduled = generatePaydays(startOfDay(date), endOfDay(date))
        return (actual + scheduled).sortedBy { it.date }
    }

    private fun getEntriesForViewedMonth(): List<CalendarEntry> {
        val first = getCalendar(viewedMonth.time).apply { set(Calendar.DAY_OF_MONTH, 1) }
        val last = getCalendar(viewedMonth.time).apply {
            set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
        }
        val start = startOfDay(first.time)
        val end = endOfDay(last.time)
        val actual = calendarEntries.filter { !it.date.before(start) && !it.date.after(end) }
        val scheduled = generatePaydays(start, end)
        return (actual + scheduled).distinctBy { "${it.source}:${it.id}" }.sortedBy { it.date }
    }

    // =====================================================
    // ADD CUSTOM CALENDAR EVENT (LOCAL-FIRST)
    // =====================================================

    private fun showAddEventDialog() {
        val titleInput = EditText(this).apply {
            hint = "Event title"
            setSingleLine(true)
            textSize = 13f
        }
        val descriptionInput = EditText(this).apply {
            hint = "Description (optional)"
            textSize = 13f
            minLines = 2
        }
        val amountInput = EditText(this).apply {
            hint = "Amount (optional)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            textSize = 13f
        }

        var eventDate = selectedDate
        val dateButton = TextView(this).apply {
            text = formatFullDate(eventDate)
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(darkText)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedBackground(Color.WHITE, borderColor, 9)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                showEventDatePicker(eventDate) { chosen ->
                    eventDate = chosen
                    text = formatFullDate(chosen)
                }
            }
        }

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        form.addView(createFormLabel("Event Title *"))
        form.addView(titleInput, matchWrapParams())
        form.addView(createFormLabel("Date"))
        form.addView(dateButton, matchWrapParams())
        form.addView(createFormLabel("Description"))
        form.addView(descriptionInput, matchWrapParams())
        form.addView(createFormLabel("Amount (Optional)"))
        form.addView(amountInput, matchWrapParams())

        val dialog = AlertDialog.Builder(this)
            .setTitle("Add Calendar Event")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save Event", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = titleInput.text.toString().trim()
                if (title.isBlank()) {
                    titleInput.error = "Enter an event title"
                    titleInput.requestFocus()
                    return@setOnClickListener
                }
                val amountText = amountInput.text.toString().trim()
                val amount = if (amountText.isBlank()) 0.0 else parseAmount(amountText)
                if (!amount.isFinite() || amount < 0.0) {
                    amountInput.error = "Enter a valid non-negative amount"
                    amountInput.requestFocus()
                    return@setOnClickListener
                }
                saveCalendarEvent(
                    title,
                    descriptionInput.text.toString().trim(),
                    eventDate,
                    amount,
                    dialog
                )
            }
        }
        dialog.show()
    }

    private fun matchWrapParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun createFormLabel(label: String) = TextView(this).apply {
        text = label
        setTextColor(darkText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(12), 0, dp(5))
    }

    private fun showEventDatePicker(initialDate: Date, onDateSelected: (Date) -> Unit) {
        val calendar = getCalendar(initialDate)
        DatePickerDialog(
            this,
            { _, year, month, day ->
                val chosen = Calendar.getInstance(timezone).apply {
                    clear()
                    set(year, month, day, 12, 0, 0)
                }
                onDateSelected(chosen.time)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun saveCalendarEvent(
        title: String,
        description: String,
        date: Date,
        amount: Double,
        dialog: AlertDialog
    ) {
        val eventId = UUID.randomUUID().toString()
        val now = Timestamp.now()
        val eventData = hashMapOf<String, Any>(
            "title" to title,
            "description" to description,
            "type" to "Custom",
            "date" to Timestamp(date),
            "eventDate" to Timestamp(date),
            "dateString" to dateKey(date),
            "amount" to amount,
            "status" to "Scheduled",
            "createdAt" to now,
            "updatedAt" to now
        )

        val documents = loadedData["calendarEvents"].orEmpty().toMutableList()
        documents.removeAll { it.id == eventId }
        documents.add(CalendarDocument(eventId, eventData))
        loadedData["calendarEvents"] = documents
        pendingEventIds.add(eventId)
        saveLocalCache()
        buildCalendarEntries()
        renderCalendar()
        dialog.dismiss()
        showToast("Event saved on this device. Syncing to your account…")

        db.collection("users").document(userId)
            .collection("calendarEvents").document(eventId)
            .set(eventData)
            .addOnSuccessListener {
                pendingEventIds.remove(eventId)
                saveLocalCache()
                showToast("Calendar event saved and synced.")
            }
            .addOnFailureListener { exception ->
                // The event remains in the local cache and is retried on the next refresh.
                saveLocalCache()
                showError("Event is saved locally; cloud sync is pending. ${exception.localizedMessage.orEmpty()}")
            }
    }

    private fun syncPendingCalendarEvents() {
        if (pendingEventIds.isEmpty()) return
        val events = loadedData["calendarEvents"].orEmpty().associateBy { it.id }
        pendingEventIds.toList().forEach { eventId ->
            val event = events[eventId] ?: return@forEach
            db.collection("users").document(userId)
                .collection("calendarEvents").document(eventId)
                .set(event.data)
                .addOnSuccessListener {
                    pendingEventIds.remove(eventId)
                    saveLocalCache()
                }
                .addOnFailureListener { exception ->
                    android.util.Log.w("CalendarActivity", "Calendar event sync pending: $eventId", exception)
                }
        }
    }

    // =====================================================
    // NEXT PAYDAY
    // =====================================================

    private fun updateNextPayday() {
        val candidates = mutableListOf<CalendarEntry>()
        val today = startOfDay(Date())
        val base = getCalendar(Date()).apply { set(Calendar.DAY_OF_MONTH, 1) }

        for (offset in 0..12) {
            val month = getCalendar(base.time).apply { add(Calendar.MONTH, offset) }
            val year = month.get(Calendar.YEAR)
            val monthNumber = month.get(Calendar.MONTH)
            val firstDate = paydayDate(year, monthNumber, paydayDayOne)
            val secondDate = paydayDate(year, monthNumber, paydayDayTwo)

            if (!hasSalaryOnDate(firstDate)) {
                candidates.add(CalendarEntry(
                    id = "next_first_${year}_$monthNumber",
                    date = firstDate,
                    title = "Payday · Day $paydayDayOne",
                    description = "First scheduled payday",
                    type = "Payday",
                    amount = paydayAmountOne,
                    source = "paydaySchedule"
                ))
            }
            if (!sameDay(firstDate, secondDate) && !hasSalaryOnDate(secondDate)) {
                candidates.add(CalendarEntry(
                    id = "next_second_${year}_$monthNumber",
                    date = secondDate,
                    title = if (paydayDayTwo == 0) "Month-end Payday" else "Payday · Day $paydayDayTwo",
                    description = "Second scheduled payday",
                    type = "Payday",
                    amount = paydayAmountTwo,
                    source = "paydaySchedule"
                ))
            }
        }

        val next = candidates.filter { !it.date.before(today) }.minByOrNull { it.date }
        if (next == null) {
            nextPaydayDate = null
            tvNextPaydayName.text = "No payday scheduled"
            tvNextPaydayDate.text = "Check your payday settings"
            tvNextPaydayAmount.text = "Not available"
            tvPaydayCountdown.text = "Not scheduled"
            return
        }

        nextPaydayDate = next.date
        tvNextPaydayName.text = next.title
        tvNextPaydayDate.text = formatFullDate(next.date)
        tvNextPaydayAmount.text = if (next.amount > 0.0) formatCurrency(next.amount) else "Expected amount not set"
        val days = daysBetween(today, startOfDay(next.date))
        tvPaydayCountdown.text = when (days) {
            0L -> "Today"
            1L -> "Tomorrow"
            else -> "In $days days"
        }
    }

    private fun daysBetween(start: Date, end: Date): Long {
        val startDay = getCalendar(start).apply { set(Calendar.HOUR_OF_DAY, 0) }
        val endDay = getCalendar(end).apply { set(Calendar.HOUR_OF_DAY, 0) }
        return ((endDay.timeInMillis - startDay.timeInMillis) / 86_400_000L).coerceAtLeast(0L)
    }

    // =====================================================
    // FINANCIAL FORECAST
    // =====================================================

    private fun updateFinancialForecast() {
        var recordedIncome = 0.0
        var expenses = 0.0
        var payments = 0.0
        var savingsContributions = 0.0
        var savingsWithdrawals = 0.0

        loadedData["salaries"].orEmpty().forEach {
            recordedIncome += amountFrom(it.data, "netSalary", "netAmount", "netPay", "takeHomePay", "amount")
        }
        loadedData["expenses"].orEmpty().forEach {
            expenses += amountFrom(it.data, "amount", "total")
        }
        loadedData["payments"].orEmpty().forEach {
            payments += amountFrom(it.data, "amount", "paymentAmount", "paidAmount")
        }
        loadedData["savings"].orEmpty().forEach { doc ->
            val amount = amountFrom(doc.data, "amount", "transactionAmount")
            val type = stringFrom(doc.data, "transactionType", "type", "movementType").lowercase(Locale.ROOT)
            if (type.contains("withdraw")) savingsWithdrawals += amount else savingsContributions += amount
        }

        val openingBalance = profileData?.let { amountFrom(it, "openingBalance", "initialBalance") } ?: 0.0
        val availableCash = openingBalance + recordedIncome - expenses - payments - savingsContributions + savingsWithdrawals
        val today = startOfDay(Date())
        val paydayLimit = nextPaydayDate ?: endOfDay(Date())
        var upcomingObligations = 0.0
        var upcomingCount = 0

        loadedData["obligations"].orEmpty().forEach { doc ->
            val dueDate = documentDate(doc.data, "dueDate", "date", "paymentDate") ?: return@forEach
            val status = stringFrom(doc.data, "status")
            if (isPaidStatus(status) || status.equals("cancelled", ignoreCase = true)) return@forEach
            if (dueDate.before(today) || dueDate.after(paydayLimit)) return@forEach
            val remaining = remainingObligation(doc.data)
            if (remaining > 0.0) {
                upcomingObligations += remaining
                upcomingCount++
            }
        }

        val remainingCash = availableCash - upcomingObligations
        tvForecastCashBalance.text = formatCurrency(availableCash)
        tvForecastObligations.text = formatCurrency(upcomingObligations)
        tvForecastRemainingCash.text = formatCurrency(remainingCash)
        tvForecastRemainingCash.setTextColor(if (remainingCash >= 0.0) green else red)
        tvForecastExplanation.text =
            "Estimated cash after $upcomingCount unpaid obligation(s) due on or before your next payday. This uses recorded income, expenses, payments, savings transfers and opening balance; unrecorded transactions and future income are not included. Avoid recording the same payment as both an expense and a separate payment."
    }

    // =====================================================
    // NORMALIZED DOCUMENT FIELD HELPERS
    // =====================================================

    private fun amountFrom(data: Map<String, Any?>, vararg fields: String): Double {
        for (field in fields) {
            if (data.containsKey(field)) return parseAmount(data[field])
        }
        return 0.0
    }

    private fun intFrom(data: Map<String, Any?>, field: String): Int = when (val value = data[field]) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull() ?: 0
        else -> 0
    }

    private fun stringFrom(data: Map<String, Any?>, vararg fields: String): String {
        for (field in fields) {
            val value = data[field]
            if (value is String && value.isNotBlank()) return value.trim()
        }
        return ""
    }

    private fun parseAmount(value: Any?): Double = when (value) {
        is Number -> value.toDouble()
        is String -> {
            val cleaned = value.trim().replace(",", "").replace(Regex("[^0-9.\\-]"), "")
            cleaned.toDoubleOrNull() ?: 0.0
        }
        else -> 0.0
    }

    private fun documentDate(data: Map<String, Any?>, vararg fields: String): Date? {
        for (field in fields) {
            when (val value = data[field]) {
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
                    timeZone = timezone
                }
                formatter.parse(value)?.let { return it }
            } catch (_: Exception) {
                // Try the next format.
            }
        }
        return null
    }

    private fun isPaidStatus(status: String): Boolean = status.equals("paid", true) ||
            status.equals("completed", true) || status.equals("fully paid", true)

    private fun remainingObligation(data: Map<String, Any?>): Double {
        val explicitFields = listOf("remainingBalance", "outstandingAmount", "unpaidAmount")
        for (field in explicitFields) {
            if (data.containsKey(field)) return parseAmount(data[field]).coerceAtLeast(0.0)
        }
        val total = amountFrom(data, "amount", "totalAmount")
        val paid = amountFrom(data, "paidAmount", "amountPaid")
        return (total - paid).coerceAtLeast(0.0)
    }

    // =====================================================
    // DATE HELPERS: ASIA/MANILA
    // =====================================================

    private fun getCalendar(date: Date): Calendar = Calendar.getInstance(timezone).apply { time = date }

    private fun dateInViewedMonth(day: Int): Date = getCalendar(viewedMonth.time).apply {
        set(Calendar.DAY_OF_MONTH, day)
        set(Calendar.HOUR_OF_DAY, 12)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.time

    private fun startOfDay(date: Date): Date = getCalendar(date).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.time

    private fun endOfDay(date: Date): Date = getCalendar(date).apply {
        set(Calendar.HOUR_OF_DAY, 23)
        set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59)
        set(Calendar.MILLISECOND, 999)
    }.time

    private fun sameDay(first: Date, second: Date): Boolean = dateKey(first) == dateKey(second)

    private fun dateKey(date: Date): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = timezone
    }.format(date)

    private fun formatDate(date: Date): String = SimpleDateFormat("MMM d, yyyy", Locale.US).apply {
        timeZone = timezone
    }.format(date)

    private fun formatFullDate(date: Date): String = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US).apply {
        timeZone = timezone
    }.format(date)

    private fun formatCurrency(amount: Double): String = NumberFormat.getCurrencyInstance(Locale("en", "PH")).format(amount)

    // =====================================================
    // UI HELPERS
    // =====================================================

    private fun roundedBackground(fillColor: Int, strokeColor: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), strokeColor)
        }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()

    private fun setLoading(loading: Boolean) {
        tvCalendarLoading.visibility = if (loading) View.VISIBLE else View.GONE
        calendarProgress.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun showError(message: String) {
        tvCalendarError.text = message
        tvCalendarError.visibility = View.VISIBLE
    }

    private fun clearError() {
        tvCalendarError.text = ""
        tvCalendarError.visibility = View.GONE
    }

    private fun hideKeyboard() {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
        currentFocus?.clearFocus()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
