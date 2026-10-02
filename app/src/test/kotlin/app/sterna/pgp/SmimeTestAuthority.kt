package app.sterna.pgp

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.InvalidKeyException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Provider
import java.security.PublicKey
import java.security.Security
import java.security.Signature
import java.security.SignatureSpi
import java.security.cert.X509Certificate
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * A root, an intermediate under it, and the leaves they issue, made for the run; no key is kept. Leaves are
 * for e-mail protection, `digitalSignature` and `keyEncipherment`, their address in the subject alternative name
 * unless said otherwise.
 */
internal class SmimeTestAuthority {
    private val bc = BouncyCastleProvider()
    private var serial = 1000L
    private val extensions = JcaX509ExtensionUtils()

    val rootKeys: KeyPair = rsa()
    val rootName = X500Name("CN=Sign Test Root, O=Sterna tests")
    val root: X509Certificate = issue(rootName, rootKeys.public, rootName, rootKeys.private, authority = true)

    val intermediateKeys: KeyPair = rsa()
    val intermediateName = X500Name("CN=Sign Test Intermediate, O=Sterna tests")
    val intermediate: X509Certificate =
        issue(intermediateName, intermediateKeys.public, rootName, rootKeys.private, authority = true)

    fun rsa(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    /** A leaf the intermediate issues, with [keys], valid from [notBefore] to [notAfter]. */
    fun leaf(
        subject: String,
        keys: KeyPair,
        address: String? = null,
        notBefore: Date = Date(System.currentTimeMillis() - DAY),
        notAfter: Date = Date(System.currentTimeMillis() + 3650 * DAY),
        usage: Int = KeyUsage.digitalSignature or KeyUsage.keyEncipherment,
        purpose: KeyPurposeId = KeyPurposeId.id_kp_emailProtection,
    ): X509Certificate = issue(
        X500Name(subject), keys.public, intermediateName, intermediateKeys.private,
        address = address, notBefore = notBefore, notAfter = notAfter, usage = usage, purpose = purpose,
    )

    /** A leaf that signs itself, no authority above it. */
    fun selfSigned(subject: String, keys: KeyPair, address: String): X509Certificate =
        issue(X500Name(subject), keys.public, X500Name(subject), keys.private, address = address)

    private fun issue(
        subject: X500Name,
        publicKey: PublicKey,
        issuer: X500Name,
        issuerKey: PrivateKey,
        authority: Boolean = false,
        address: String? = null,
        notBefore: Date = Date(System.currentTimeMillis() - DAY),
        notAfter: Date = Date(System.currentTimeMillis() + 3650 * DAY),
        usage: Int = KeyUsage.digitalSignature or KeyUsage.keyEncipherment,
        purpose: KeyPurposeId = KeyPurposeId.id_kp_emailProtection,
    ): X509Certificate {
        val builder = JcaX509v3CertificateBuilder(issuer, BigInteger.valueOf(serial++), notBefore, notAfter, subject, publicKey)
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(authority))
        builder.addExtension(Extension.subjectKeyIdentifier, false, extensions.createSubjectKeyIdentifier(publicKey))
        if (authority) {
            builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
        } else {
            builder.addExtension(Extension.keyUsage, true, KeyUsage(usage))
            builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(purpose))
            address?.let {
                builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(GeneralName(GeneralName.rfc822Name, it)))
            }
        }
        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(bc).build(issuerKey)
        return JcaX509CertificateConverter().setProvider(bc).getCertificate(builder.build(signer))
    }

    companion object {
        const val DAY = 86_400_000L
    }
}

/**
 * A key that implements `PrivateKey` and nothing more, as one the Android keystore holds: no provider but
 * [KeystoreOnlyProvider] can sign with it, and it refuses to be read out.
 */
internal class KeystoreOnlyKey(val inner: PrivateKey) : PrivateKey {
    var readOut = 0

    override fun getAlgorithm(): String = "RSA"

    override fun getFormat(): String? = null

    override fun getEncoded(): ByteArray? {
        readOut++
        return null
    }
}

internal class KeystoreOnlySignature : SignatureSpi() {
    private val delegate: Signature = Signature.getInstance("SHA256withRSA", "SunRsaSign")

    override fun engineInitSign(privateKey: PrivateKey) =
        delegate.initSign((privateKey as? KeystoreOnlyKey)?.inner ?: throw InvalidKeyException("not a KeystoreOnlyKey"))

    override fun engineInitVerify(publicKey: PublicKey) = throw InvalidKeyException("this provider only signs")

    override fun engineUpdate(b: Byte) = delegate.update(b)

    override fun engineUpdate(b: ByteArray, off: Int, len: Int) = delegate.update(b, off, len)

    override fun engineSign(): ByteArray = delegate.sign()

    override fun engineVerify(sigBytes: ByteArray): Boolean = throw UnsupportedOperationException()

    override fun engineSetParameter(param: String?, value: Any?) = throw UnsupportedOperationException()

    override fun engineGetParameter(param: String?): Any = throw UnsupportedOperationException()

    override fun engineGetParameters(): AlgorithmParameters? = null
}

/** The keystore's provider, in miniature: SHA-256 with RSA for a [KeystoreOnlyKey], and nothing else. */
internal class KeystoreOnlyProvider : Provider("SternaTestSigningKeystore", 1.0, "SHA256withRSA for KeystoreOnlyKey only") {
    init {
        putService(
            object : Provider.Service(this, "Signature", "SHA256withRSA", KeystoreOnlySignature::class.java.name, null, null) {
                override fun newInstance(constructorParameter: Any?): Any = KeystoreOnlySignature()
            },
        )
    }
}

/**
 * [block] with [KeystoreOnlyProvider] registered, and removed after. Registration cannot be avoided: the engine
 * names no provider for the key, on purpose, so the JCA finds it among the registered ones, as on Android.
 */
internal fun <T> withKeystoreProvider(block: () -> T): T {
    val keystore = KeystoreOnlyProvider()
    Security.addProvider(keystore)
    try {
        return block()
    } finally {
        Security.removeProvider(keystore.name)
    }
}
