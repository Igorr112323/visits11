package ru.kubgau.attendance.terminal.net

import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Один OkHttp-клиент на всё приложение: короткие таймауты (это локальная сеть),
 * без логов в release, с повтором только там, где это безопасно (GET).
 */
object ApiFactory {

    // baseUrl нужен формально: настоящий адрес подставляется через @Url в каждом вызове.
    private const val FAKE_BASE_URL = "http://127.0.0.1/"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(FAKE_BASE_URL)
            .client(client)
            .addConverterFactory(
                GsonConverterFactory.create(GsonBuilder().serializeNulls().create()),
            )
            .build()
    }

    val api: AttendanceApi by lazy { retrofit.create(AttendanceApi::class.java) }

    fun api(@Suppress("UNUSED_PARAMETER") url: String): AttendanceApi = api
}
