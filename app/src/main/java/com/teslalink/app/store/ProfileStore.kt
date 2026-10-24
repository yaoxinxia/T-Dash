package com.teslalink.app.store

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** 事故取证/报案要反复填的个人信息。全部只存在本机。 */
data class AccidentProfile(
    val name: String = "",
    val phone: String = "",
    val plate: String = "",
    val vin: String = "",
    val insurer: String = "",
    val policyNo: String = "",
) {
    val summary: String
        get() = listOfNotNull(
            name.takeIf { it.isNotBlank() },
            plate.takeIf { it.isNotBlank() },
            phone.takeIf { it.isNotBlank() },
        ).joinToString(" ")
}

/**
 * 取证个人信息存储：文字走 DataStore；证件照**复制**进应用私有目录。
 *
 * 为什么必须复制：系统照片选择器给的是临时读授权的 content URI，进程一重启
 * 就再也读不出来——存 URI 等于存了个会过期的引用。
 */
class ProfileStore(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val prefs = PrefsStore(app)
    private val dir = File(app.filesDir, "profile").apply { mkdirs() }

    val profile = MutableStateFlow(AccidentProfile())
    /** 驾驶证照片（私有目录文件的 Uri）。 */
    val license = MutableStateFlow<Uri?>(null)
    /** 行驶证照片。 */
    val vehicle = MutableStateFlow<Uri?>(null)

    init {
        scope.launch {
            profile.value = AccidentProfile(
                name = prefs.getString(K_NAME).orEmpty(),
                phone = prefs.getString(K_PHONE).orEmpty(),
                plate = prefs.getString(K_PLATE).orEmpty(),
                vin = prefs.getString(K_VIN).orEmpty(),
                insurer = prefs.getString(K_INSURER).orEmpty(),
                policyNo = prefs.getString(K_POLICY).orEmpty(),
            )
            license.value = readPhoto(KEY_LICENSE)
            vehicle.value = readPhoto(KEY_VEHICLE)
        }
    }

    suspend fun save(p: AccidentProfile) {
        profile.value = p
        prefs.putStrings(
            mapOf(
                K_NAME to p.name.trim(),
                K_PHONE to p.phone.trim(),
                K_PLATE to p.plate.trim(),
                K_VIN to p.vin.trim().uppercase(),
                K_INSURER to p.insurer.trim(),
                K_POLICY to p.policyNo.trim(),
            ),
        )
    }

    /**
     * 把选来的图片复制成私有文件；成功返回 false 表示源读不出或写不进。
     * **先写临时文件再原子替换**：直接覆盖目标时，复制中途失败会把已有的旧照片一并毁掉，
     * 而 prefs 还指着那个路径 → 界面读不出、PDF 占位。临时文件失败只删临时文件，旧照无损。
     */
    suspend fun importPhoto(kind: String, src: Uri): Boolean {
        val file = File(dir, "$kind.jpg")
        val tmp = File(dir, "$kind.jpg.tmp")
        val copied = runCatching {
            app.contentResolver.openInputStream(src)?.use { input ->
                tmp.outputStream().use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            } != null
        }.getOrDefault(false) && tmp.length() > 0
        if (!copied) {
            runCatching { tmp.delete() }
            return false
        }
        if (file.exists()) runCatching { file.delete() }
        if (!tmp.renameTo(file)) {
            runCatching { tmp.copyTo(file, overwrite = true) }
            runCatching { tmp.delete() }
        }
        prefs.putString(if (kind == KIND_LICENSE) KEY_LICENSE else KEY_VEHICLE, file.absolutePath)
        (if (kind == KIND_LICENSE) license else vehicle).value = Uri.fromFile(file)
        return true
    }

    suspend fun clearPhoto(kind: String) {
        val key = if (kind == KIND_LICENSE) KEY_LICENSE else KEY_VEHICLE
        runCatching { File(dir, "$kind.jpg").delete() }
        prefs.remove(key)
        (if (kind == KIND_LICENSE) license else vehicle).value = null
    }

    /** 供 PDF 导出用：证件照是我们自己的私有文件，直接按路径读，不依赖任何授权。 */
    fun photoFile(uri: Uri?): File? = uri?.path?.let { File(it) }?.takeIf { it.exists() }

    private suspend fun readPhoto(key: String): Uri? =
        prefs.getString(key)?.let { File(it) }?.takeIf { it.exists() }?.let { Uri.fromFile(it) }

    companion object {
        const val KIND_LICENSE = "license"
        const val KIND_VEHICLE = "vehicle"
        const val KEY_LICENSE = "profile_photo_license"
        const val KEY_VEHICLE = "profile_photo_vehicle"
        private const val K_NAME = "profile_name"
        private const val K_PHONE = "profile_phone"
        private const val K_PLATE = "profile_plate"
        private const val K_VIN = "profile_vin"
        private const val K_INSURER = "profile_insurer"
        private const val K_POLICY = "profile_policy"
    }
}
