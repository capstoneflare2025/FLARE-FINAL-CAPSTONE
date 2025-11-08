package com.example.flare_capstone

import android.os.AsyncTask
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class FetchBarangayAddressTask(
    private val activity: Any,
    private val latitude: Double,
    private val longitude: Double
) : AsyncTask<Void, Void, String?>() {

    override fun doInBackground(vararg params: Void?): String? {
        // 1) Quick Overpass relations for barangay + city
        val rels = queryOverpassRelations(latitude, longitude)
        var barangay = pickBarangayFromRelations(rels)?.let { normalizeBarangay(it) }
        var city     = pickCityFromRelations(rels)?.let { normalizeCity(it) }

        // 2) Single Nominatim fallback for whatever is missing
        if (barangay == null || city == null) {
            val nomi = queryFromNominatim(latitude, longitude)
            if (barangay == null) {
                barangay = nomi.barangay?.let { normalizeBarangay(it) }
            }
            if (city == null) {
                city = nomi.city?.let { normalizeCity(it) }
            }
        }

        // 3) If still nothing useful, give up (caller will handle)
        if (barangay.isNullOrBlank() && city.isNullOrBlank()) return null

        // 4) Compose strictly: Barangay + City (only)
        val bClean = barangay?.replace(Regex("(?i)^Barangay\\s+"), "")?.trim()
        val cClean = city?.trim()
        // Avoid “Barangay San Miguel, San Miguel City” duplication
        val finalBarangay = if (!bClean.isNullOrEmpty() && !cClean.isNullOrEmpty() && bClean.equals(cClean, true)) null else barangay

        return listOfNotNull(finalBarangay, city)
            .filter { it.isNotBlank() }
            .joinToString(", ")
            .ifBlank { null }
    }

    override fun onPostExecute(result: String?) {
        super.onPostExecute(result)
        when (activity) {
            is FireLevelActivity -> activity.handleFetchedAddress(result)
            is OtherEmergencyActivity -> activity.handleFetchedAddress(result)
            is EmergencyMedicalServicesActivity -> activity.handleFetchedAddress(result)
            is DashboardActivity -> activity.handleFetchedAddress(result)
        }
    }

    /* ---------- Overpass (relations only, fast) ---------- */
    private fun queryOverpassRelations(lat: Double, lon: Double): JSONArray {
        val q = """
            [out:json][timeout:8];
            is_in($lat,$lon)->.a;
            rel.a["boundary"="administrative"];
            out tags;
        """.trimIndent()
        return overpassPost(q)
    }

    private fun pickBarangayFromRelations(elements: JSONArray): String? {
        var fallback: String? = null
        for (i in 0 until elements.length()) {
            val tags = elements.optJSONObject(i)?.optJSONObject("tags") ?: continue
            if (tags.optString("admin_level") == "10") {
                val name = bestName(tags) ?: continue
                val looksBrgy =
                    tags.keys().asSequence().any { k ->
                        val v = tags.optString(k).lowercase()
                        k.lowercase().contains("barangay") || v.contains("barangay") || v.startsWith("brgy")
                    } || tags.optString("admin_type:PH").equals("barangay", true)
                if (looksBrgy) return name
                if (fallback == null) fallback = name
            }
        }
        return fallback
    }

    private fun pickCityFromRelations(elements: JSONArray): String? {
        var byPlace: String? = null
        var byLevel: String? = null
        for (i in 0 until elements.length()) {
            val tags = elements.optJSONObject(i)?.optJSONObject("tags") ?: continue
            val place = tags.optString("place").lowercase()
            val level = tags.optString("admin_level")
            val name  = bestName(tags) ?: continue
            when (place) {
                "city" -> return name
                "municipality", "town" -> if (byPlace == null) byPlace = name
            }
            if (level in listOf("7","8","6") && byLevel == null) byLevel = name
        }
        return byPlace ?: byLevel
    }

    /* ---------- Nominatim single reverse for both fields ---------- */
    private data class Nomi(val barangay: String?, val city: String?)
    private fun queryFromNominatim(lat: Double, lon: Double): Nomi {
        val u = URL("https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lon&format=json&addressdetails=1&zoom=18")
        var conn: HttpURLConnection? = null
        return try {
            conn = (u.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "FlareCapstone/1.0 (contact: you@example.com)")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Language", "en-PH,en;q=0.8")
                connectTimeout = 3000
                readTimeout = 4000
            }
            val code = conn.responseCode
            if (code !in 200..299) return Nomi(null, null)
            val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            val addr = JSONObject(body).optJSONObject("address") ?: return Nomi(null, null)

            val brgy = listOf("barangay", "city_district", "suburb", "village")
                .firstNotNullOfOrNull { k -> addr.optString(k).takeIf { it.isNotBlank() } }

            val city = listOf("city","town","municipality")
                .firstNotNullOfOrNull { k -> addr.optString(k).takeIf { it.isNotBlank() } }

            Nomi(brgy, city)
        } catch (_: Exception) {
            Nomi(null, null)
        } finally { conn?.disconnect() }
    }

    /* ---------- Shared helpers ---------- */
    private fun overpassPost(query: String): JSONArray {
        val mirrors = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"
        )
        for (ep in mirrors) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(ep).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("User-Agent", "FlareCapstone/1.0 (contact: you@example.com)")
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    connectTimeout = 3000
                    readTimeout   = 5000
                }
                val body = "data=" + URLEncoder.encode(query, "UTF-8")
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                val txt  = BufferedReader(InputStreamReader(
                    if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
                )).use { it.readText() }
                if (code in 200..299) {
                    return JSONObject(txt).optJSONArray("elements") ?: JSONArray()
                } else {
                    Log.e("Overpass", "HTTP $code from $ep: $txt")
                }
            } catch (e: Exception) {
                Log.e("Overpass", "Error with $ep: ${e.message}")
            } finally { conn?.disconnect() }
        }
        return JSONArray()
    }

    private fun bestName(tags: JSONObject): String? =
        tags.optString("name:en").takeIf { it.isNotBlank() }
            ?: tags.optString("official_name").takeIf { it.isNotBlank() }
            ?: tags.optString("name").takeIf { it.isNotBlank() }

    private fun normalizeBarangay(raw: String): String =
        if (Regex("""(?i)^(brgy\.?|barangay)\b""").containsMatchIn(raw.trim())) raw.trim()
        else "Barangay ${raw.trim()}"

    private fun normalizeCity(raw: String): String =
        if (Regex("""(?i)\bcity\b""").containsMatchIn(raw.trim())) raw.trim()
        else "${raw.trim()} City"
}
