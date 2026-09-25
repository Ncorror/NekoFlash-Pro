package com.ncorror.nekoflash.pro.platform.usb

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.ncorror.nekoflash.pro.adb.AdbHostKey
import com.ncorror.nekoflash.pro.adb.AdbKeyEncoding
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAKeyGenParameterSpec

/** A persistent, non-exportable host identity belonging only to this installation. */
internal class AdbKeystoreHostKey private constructor(
    private val privateKey: java.security.PrivateKey,
    private val publicKey: RSAPublicKey,
) : AdbHostKey {
    override fun signChallenge(token: ByteArray): ByteArray = Signature.getInstance("NONEwithRSA").run {
        initSign(privateKey)
        update(AdbKeyEncoding.sha1DigestInfo(token))
        sign()
    }

    override fun publicKeyText(): ByteArray = AdbKeyEncoding.publicKeyText(publicKey)

    companion object {
        private const val ALIAS = "nekoflash-adb-host-v1"

        /** Called only from the background ADB connection thread after USB access is granted. */
        fun getOrCreate(): AdbKeystoreHostKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!store.containsAlias(ALIAS)) {
                val parameters = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(RSAKeyGenParameterSpec(2048,
                        RSAKeyGenParameterSpec.F4))
                    .setDigests(KeyProperties.DIGEST_NONE)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .build()
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
                    .apply { initialize(parameters) }.generateKeyPair()
            }
            val private = store.getKey(ALIAS, null) as? java.security.PrivateKey
                ?: error("ADB host private key unavailable")
            val public = store.getCertificate(ALIAS)?.publicKey as? RSAPublicKey
                ?: error("ADB host public key unavailable")
            return AdbKeystoreHostKey(private, public)
        }
    }
}
