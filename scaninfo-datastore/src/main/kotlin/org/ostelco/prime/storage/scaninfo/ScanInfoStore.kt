package org.ostelco.prime.storage.scaninfo

import arrow.core.Either
import arrow.core.fix
import arrow.effects.IO
import arrow.instances.either.monad.monad
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.cloud.datastore.Blob
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.StorageException
import com.google.cloud.storage.StorageOptions
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.JsonKeysetReader
import com.google.crypto.tink.config.TinkConfig
import com.google.crypto.tink.hybrid.HybridDecryptFactory
import io.dropwizard.setup.Environment
import org.ostelco.prime.getLogger
import org.ostelco.prime.model.JumioScanData
import org.ostelco.prime.model.VendorScanData
import org.ostelco.prime.model.VendorScanInformation
import org.ostelco.prime.storage.FileDownloadError
import org.ostelco.prime.storage.NotCreatedError
import org.ostelco.prime.storage.ScanInformationStore
import org.ostelco.prime.storage.StoreError
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.ws.rs.core.MultivaluedMap
import kotlin.collections.HashMap


class ScanInfoStore : ScanInformationStore by ScanInformationStoreSingleton

/**
 * Helper class for getting environment variables.
 * Introduced to help testing.
 */
open class EnvironmentVars {
    /**
     * Retrieve the value of the environbment variable.
     */
    open fun getVar(name: String): String? = System.getenv(name)
}

object ScanInformationStoreSingleton : ScanInformationStore {

    private val logger by getLogger()

    /* Generated by Jumio and can be obtained from the console. */
    private lateinit var apiToken: String
    private lateinit var apiSecret: String

    private lateinit var storageBucket: String

    // Path name prefix for the keyset files.
    private lateinit var keysetFilePathPrefix: String
    // KMS key name for decrypting the public key set.
    private var masterKeyUri: String? = null
    // Encryptors for used for each country
    private var encrypters:HashMap<String, ScanInfoEncrypt> = HashMap()

    fun getEncrypter(countryCode: String): ScanInfoEncrypt {
        if (encrypters.containsKey(countryCode)) {
            return encrypters[countryCode]!!
        } else {
            val encrypt = ScanInfoEncrypt("${keysetFilePathPrefix}_${countryCode}", masterKeyUri)
            encrypters.put(countryCode, encrypt)
            return encrypt
        }
    }

    override fun upsertVendorScanInformation(subscriberId: String, countryCode:String, vendorData: MultivaluedMap<String, String>): Either<StoreError, Unit> {
        return IO {
            Either.monad<StoreError>().binding {
                val vendorScanInformation = createVendorScanInformation(vendorData).bind()
                // TODO: find the right bucket for this scan (may be they are in a different region)
                val bucketName = storageBucket
                val plainZipData = JumioHelper.generateZipFile(vendorScanInformation).bind()
                val zipData = getEncrypter(countryCode).encryptData(plainZipData)
                if (bucketName.isNullOrEmpty()) {
                    val fileName = "${countryCode}_${vendorScanInformation.scanId}.zip"
                    logger.info("No bucket set, saving file locally $fileName")
                    JumioHelper.saveZipFile(fileName, zipData).bind()
                } else {
                    val fileName = "${subscriberId}/${vendorScanInformation.scanId}.zip"
                    val globalBucket = "${bucketName}-global"
                    val countryBucket = "${bucketName}-${countryCode.toLowerCase()}"
                    logger.info("Saving in cloud store $globalBucket --> $fileName")
                    JumioHelper.uploadZipFile(globalBucket, fileName, zipData).bind()
                    if (countryBucket != globalBucket) {
                        logger.info("Saving in cloud store $countryBucket --> $fileName")
                        JumioHelper.uploadZipFile(countryBucket, fileName, zipData).bind()
                    }
                }
                Unit
            }.fix()
        }.unsafeRunSync()
    }

    private fun createVendorScanInformation(vendorData: MultivaluedMap<String, String>): Either<StoreError, VendorScanInformation> {
        return JumioHelper.generateVendorScanInformation(vendorData, apiToken, apiSecret)
    }

    internal fun __getVendorScanInformationFile(subscriberId: String, countryCode:String, scanId: String): Either<StoreError, String> {
        return Either.right("${countryCode}_$scanId.zip")
    }

    fun init(env: Environment?, environmentVars: EnvironmentVars) {
        TinkConfig.register()
        keysetFilePathPrefix = ConfigRegistry.config.keysetFilePathPrefix
        masterKeyUri = ConfigRegistry.config.masterKeyUri
        if (ConfigRegistry.config.storeType != "emulator") {
            // Don't throw error during local tests
            apiToken = environmentVars.getVar("JUMIO_API_TOKEN")
                    ?: throw Error("Missing environment variable JUMIO_API_TOKEN")
            apiSecret = environmentVars.getVar("JUMIO_API_SECRET")
                    ?: throw Error("Missing environment variable JUMIO_API_SECRET")
            storageBucket = environmentVars.getVar("SCANINFO_STORAGE_BUCKET")
                    ?: throw Error("Missing environment variable SCANINFO_STORAGE_BUCKET")
        } else {
            apiToken = ""
            apiSecret = ""
            storageBucket = ""
        }
    }

    fun cleanup() {
    }
}

/**
 * A utility for downloading and creating the scan information for Jumio clients.
 */
object JumioHelper {
    /**
     * Retrieves the contents of a file from a URL
     */
    fun downloadFileAsBlob(fileURL: String, username: String, password: String): Either<StoreError, Pair<Blob, String>> {
        val url = URL(fileURL)
        val httpConn = url.openConnection() as HttpURLConnection
        val userpass = "$username:$password"
        val authHeader = "Basic ${Base64.getEncoder().encodeToString(userpass.toByteArray())}"
        httpConn.setRequestProperty("Authorization", authHeader)

        try {
            val responseCode = httpConn.responseCode
            // always check HTTP response code first
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val statusMessage = "$responseCode: ${httpConn.responseMessage}"
                return Either.left(FileDownloadError(fileURL, statusMessage));
            }
            val contentType = httpConn.contentType
            val inputStream = httpConn.inputStream
            val fileData = Blob.copyFrom(inputStream)
            inputStream.close()
            return Either.right(Pair(fileData, contentType))
        } catch (e: IOException) {
            val statusMessage = "IOException: $e"
            return Either.left(FileDownloadError(fileURL, statusMessage))
        } finally {
            httpConn.disconnect()
        }
    }

    fun generateVendorScanInformation(vendorData: MultivaluedMap<String, String>, apiToken: String, apiSecret: String): Either<StoreError, VendorScanInformation> {
        var scanImage: Blob? = null
        var scanImageType: String? = null
        var scanImageBackside: Blob? = null
        var scanImageBacksideType: String? = null
        var scanImageFace: Blob? = null
        var scanImageFaceType: String? = null

        val scanId: String = vendorData.getFirst(JumioScanData.SCAN_ID.s)
        val scanDetails: String = ObjectMapper().writeValueAsString(vendorData)
        val scanImageUrl: String? = vendorData.getFirst(JumioScanData.SCAN_IMAGE.s)
        val scanImageBacksideUrl: String? = vendorData.getFirst(JumioScanData.SCAN_IMAGE_BACKSIDE.s)
        val scanImageFaceUrl: String? = vendorData.getFirst(JumioScanData.SCAN_IMAGE_FACE.s)

        return IO {
            Either.monad<StoreError>().binding {
                var result: Pair<Blob, String>
                if (scanImageUrl != null) {
                    result = downloadFileAsBlob(scanImageUrl, apiToken, apiSecret).bind()
                    scanImage = result.first
                    scanImageType = result.second
                }
                if (scanImageBacksideUrl != null) {
                    result = downloadFileAsBlob(scanImageBacksideUrl, apiToken, apiSecret).bind()
                    scanImageBackside = result.first
                    scanImageBacksideType = result.second
                }
                if (scanImageFaceUrl != null) {
                    result = downloadFileAsBlob(scanImageFaceUrl, apiToken, apiSecret).bind()
                    scanImageFace = result.first
                    scanImageFaceType = result.second
                }
                VendorScanInformation(
                        scanId,
                        scanDetails,
                        scanImage,
                        scanImageType,
                        scanImageBackside,
                        scanImageBacksideType,
                        scanImageFace,
                        scanImageFaceType
                )
            }.fix()
        }.unsafeRunSync()
    }

    fun generateZipFile(vendorData: VendorScanInformation): Either<StoreError, ByteArray> {
        val outputStream = ByteArrayOutputStream()
        val zos = ZipOutputStream(BufferedOutputStream(outputStream))

        try {
            zos.putNextEntry(ZipEntry("postdata.json"))
            zos.write(vendorData.scanDetails.toByteArray())
            zos.closeEntry()
            if (vendorData.scanImage != null && vendorData.scanImageType != null) {
                zos.putNextEntry(ZipEntry("id.${getFileExtFromType(vendorData.scanImageType!!)}"))
                zos.write(vendorData.scanImage!!.toByteArray())
                zos.closeEntry()
            }
            if (vendorData.scanImageBackside != null && vendorData.scanImageBacksideType != null) {
                zos.putNextEntry(ZipEntry("id_backside.${getFileExtFromType(vendorData.scanImageBacksideType!!)}"))
                zos.write(vendorData.scanImageBackside!!.toByteArray())
                zos.closeEntry()
            }
            if (vendorData.scanImageFace != null && vendorData.scanImageFaceType != null) {
                zos.putNextEntry(ZipEntry("id_face.${getFileExtFromType(vendorData.scanImageFaceType!!)}"))
                zos.write(vendorData.scanImageFace!!.toByteArray())
                zos.closeEntry()
            }
            zos.finish()
        } catch (e: IOException) {
            return Either.left(NotCreatedError(VendorScanData.TYPE_NAME.s, vendorData.scanId))
        } finally {
            zos.close()
        }
        return Either.right(outputStream.toByteArray());
    }

    fun getFileExtFromType(mimeType: String): String {
        val idx = mimeType.lastIndexOf("/")
        if (idx == -1) {
            return mimeType
        } else {
            return mimeType.drop(idx + 1)
        }
    }

    fun uploadZipFile(bucket: String, fileName: String, data: ByteArray): Either<StoreError, String> {
        val storage = StorageOptions.getDefaultInstance().getService()
        val blobId = BlobId.of(bucket, fileName)
        val blobInfo = BlobInfo.newBuilder(blobId).setContentType("application/octet-stream").build()
        var mediaLink: String
        try {
            val blob = storage.create(blobInfo, data)
            mediaLink = blob.mediaLink
        } catch (e: StorageException) {
            return Either.left(NotCreatedError(VendorScanData.TYPE_NAME.s, "$bucket/$fileName"))
        }
        return Either.right(mediaLink);
    }

    fun saveZipFile(fileName: String, data: ByteArray): Either<StoreError, String> {
        val fos = FileOutputStream(File(fileName))
        try {
            fos.write(data)
            fos.close()
        } catch (e: IOException) {
            return Either.left(NotCreatedError(VendorScanData.TYPE_NAME.s, "$fileName"))
        }
        return Either.right(fileName);
    }

    fun loadLocalZipFile(fileName: String): Either<StoreError, ZipInputStream> {
        try {
            val fis = FileInputStream(File(fileName))
            return Either.right(ZipInputStream(fis));
        } catch (e: FileNotFoundException) {
            return Either.left(NotCreatedError(VendorScanData.TYPE_NAME.s, "$fileName"))
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val fileURL = "https://jdbc.postgresql.org/download/postgresql-9.2-1002.jdbc4.jar"
        try {
            val ret = downloadFileAsBlob(fileURL, "", "")
            println(ret)
        } catch (ex: IOException) {
            ex.printStackTrace()
        }
        __testDecryption()
    }

    fun __testDecryption() {
        // The files created during the acceptance tests can be verified using this function
        // Download encrypted files created in the root folder of prime docker image
        // Find files by logging into the docker image `docker exec -ti prime bash`
        // Copy files from docker image using `docker cp prime:/global_f1a6a509-7998-405c-b186-08983c91b422 .`
        // Replace the path for the input files in the method & run.
        TinkConfig.register()
        val file = File("global_f1a6a509-7998-405c-b186-08983c91b422.zip") // File downloaded form docker image after AT
        val fis = FileInputStream(file)
        val data = ByteArray(file.length().toInt())
        fis.read(data)
        fis.close()
        val pvtKeysetFilename = "prime/config/test_keyset_pvt_cltxt" // The test private keys used in AT
        val keysetHandle = CleartextKeysetHandle.read(JsonKeysetReader.withFile(File(pvtKeysetFilename)))
        val hybridDecrypt = HybridDecryptFactory.getPrimitive(keysetHandle)
        val decrypted = hybridDecrypt.decrypt(data, null)
        saveZipFile("decrypted.zip", decrypted)
    }
}