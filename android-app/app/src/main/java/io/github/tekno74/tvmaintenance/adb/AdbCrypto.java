package io.github.tekno74.tvmaintenance.adb;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;

import javax.crypto.Cipher;

/**
 * Module 2b (adb): Pure-Java RSA-2048 key manager and Android adbd public key encoder.
 * Zero external dependencies. Supports loading a pre-authorized PKCS#8 key or generating its own.
 */
public final class AdbCrypto {
    private static final String TAG = "TvMaintAdbCrypto";
    private static final int KEY_BITS = 2048;
    private static final int KEY_WORDS = KEY_BITS / 32; // 64 words (32-bit uints)

    // Standard ASN.1 DigestInfo header for SHA-1 used by adbd AUTH_SIGNATURE (PKCS#1 v1.5)
    private static final byte[] SHA1_DIGEST_INFO_PREFIX = new byte[]{
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };

    private final PrivateKey privateKey;
    private final RSAPublicKey publicKey;
    private final byte[] adbPublicKeyPayload;

    private AdbCrypto(PrivateKey privateKey, RSAPublicKey publicKey) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.adbPublicKeyPayload = encodeAdbPublicKey(publicKey);
    }

    public static synchronized AdbCrypto loadOrCreate(Context context) throws Exception {
        File filesDir = context.getApplicationContext().getFilesDir();
        File extDir = context.getApplicationContext().getExternalFilesDir(null);

        // Allow one-step provisioning of an already-authorized host key via external files dir
        if (extDir != null) {
            File provisioned = new File(extDir, "adbkey");
            if (provisioned.exists() && provisioned.length() > 0) {
                try {
                    AdbCrypto c = loadFromPemOrPk8File(provisioned);
                    savePkcs8(new File(filesDir, "adbkey.pk8"), c.privateKey.getEncoded());
                    // Delete external copy once imported into private storage
                    //noinspection ResultOfMethodCallIgnored
                    provisioned.delete();
                    Log.i(TAG, "Imported pre-authorized ADB key into private storage.");
                    return c;
                } catch (Exception e) {
                    Log.w(TAG, "Failed to import provisioned adbkey, falling back: " + e.getMessage());
                }
            }
        }

        File privFile = new File(filesDir, "adbkey.pk8");
        if (privFile.exists() && privFile.length() > 0) {
            return loadFromPemOrPk8File(privFile);
        }

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(KEY_BITS);
        KeyPair kp = kpg.generateKeyPair();
        savePkcs8(privFile, kp.getPrivate().getEncoded());
        Log.i(TAG, "Generated new 2048-bit RSA keypair for loopback ADB.");
        return new AdbCrypto(kp.getPrivate(), (RSAPublicKey) kp.getPublic());
    }

    private static AdbCrypto loadFromPemOrPk8File(File file) throws Exception {
        byte[] raw = new byte[(int) file.length()];
        try (FileInputStream fis = new FileInputStream(file)) {
            int off = 0;
            while (off < raw.length) {
                int r = fis.read(raw, off, raw.length - off);
                if (r < 0) break;
                off += r;
            }
        }
        String text = new String(raw, StandardCharsets.US_ASCII);
        byte[] der;
        if (text.contains("-----BEGIN")) {
            String clean = text
                    .replaceAll("-----BEGIN [A-Z ]+-----", "")
                    .replaceAll("-----END [A-Z ]+-----", "")
                    .replaceAll("\\s+", "");
            der = Base64.decode(clean, Base64.DEFAULT);
        } else {
            der = raw;
        }
        KeyFactory kf = KeyFactory.getInstance("RSA");
        PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(der));
        RSAPrivateKey rsaPriv = (RSAPrivateKey) priv;
        RSAPublicKey pub = (RSAPublicKey) kf.generatePublic(
                new RSAPublicKeySpec(rsaPriv.getModulus(), BigInteger.valueOf(65537)));
        return new AdbCrypto(priv, pub);
    }

    private static void savePkcs8(File dest, byte[] encoded) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(dest, false)) {
            fos.write(encoded);
        }
    }

    /**
     * Signs the 20-byte adbd AUTH_TOKEN using RSA/ECB/PKCS1Padding with the SHA-1 DigestInfo prefix.
     */
    public byte[] signToken(byte[] token) throws Exception {
        byte[] input = new byte[SHA1_DIGEST_INFO_PREFIX.length + token.length];
        System.arraycopy(SHA1_DIGEST_INFO_PREFIX, 0, input, 0, SHA1_DIGEST_INFO_PREFIX.length);
        System.arraycopy(token, 0, input, SHA1_DIGEST_INFO_PREFIX.length, token.length);

        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, privateKey);
        return cipher.doFinal(input);
    }

    public byte[] getAdbPublicKeyPayload() {
        return adbPublicKeyPayload;
    }

    /**
     * Encodes an RSAPublicKey into Android's 524-byte RSAPublicKey struct (little-endian),
     * Base64-encodes it, and appends " tv-maintenance@localhost\0".
     */
    private static byte[] encodeAdbPublicKey(RSAPublicKey pub) {
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n = pub.getModulus();
        BigInteger r = BigInteger.ONE.shiftLeft(KEY_BITS);
        BigInteger rr = r.multiply(r).mod(n);
        BigInteger rem = n.remainder(r32);
        BigInteger n0inv = rem.modInverse(r32).negate().mod(r32);

        int[] myN = new int[KEY_WORDS];
        int[] myRr = new int[KEY_WORDS];
        BigInteger tempN = n;
        BigInteger tempRr = rr;
        for (int i = 0; i < KEY_WORDS; i++) {
            BigInteger[] divRemN = tempN.divideAndRemainder(r32);
            tempN = divRemN[0];
            myN[i] = divRemN[1].intValue();

            BigInteger[] divRemRr = tempRr.divideAndRemainder(r32);
            tempRr = divRemRr[0];
            myRr[i] = divRemRr[1].intValue();
        }

        ByteBuffer buf = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(KEY_WORDS);
        buf.putInt(n0inv.intValue());
        for (int v : myN) buf.putInt(v);
        for (int v : myRr) buf.putInt(v);
        buf.putInt(pub.getPublicExponent().intValue());

        String b64 = Base64.encodeToString(buf.array(), Base64.NO_WRAP);
        String full = b64 + " tv-maintenance@localhost\0";
        return full.getBytes(StandardCharsets.US_ASCII);
    }
}
