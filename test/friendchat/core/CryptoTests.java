package friendchat.core;

import friendchat.core.crypto.*;
import friendchat.core.nostr.Json;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

public final class CryptoTests {
    static byte[] h(String s) { return HexFormat.of().parseHex(s); }
    static String x(byte[] b) { return HexFormat.of().formatHex(b); }

    public static void main(String[] a) throws Exception {
        // BIP-340 official vector 0
        byte[] sk = h("0000000000000000000000000000000000000000000000000000000000000003");
        byte[] pk = Secp256k1.publicKey(sk);
        T.eq("bip340 pubkey", "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9", x(pk));
        byte[] zero = new byte[32];
        byte[] sig = Secp256k1.sign(zero, sk, zero);
        T.eq("bip340 sig", "e907831f80848d1069a5371b402410364bdf1c5f8307b0084c55f1ce2dca821525f66a4a85ea8b71e482a74f382d2ce5ebeee8fdb2172f477df4900d310536c0", x(sig));
        T.ok("bip340 verify", Secp256k1.verify(zero, sig, pk));
        sig[10] ^= 1;
        T.ok("bip340 rejects tampered", !Secp256k1.verify(zero, sig, pk));

        // random roundtrip + ECDH symmetry + NIP-44
        byte[] a1 = Secp256k1.newSecret(), b1 = Secp256k1.newSecret();
        byte[] ap = Secp256k1.publicKey(a1), bp = Secp256k1.publicKey(b1);
        byte[] msg = Secp256k1.newSecret();
        T.ok("sign/verify", Secp256k1.verify(msg, Secp256k1.sign(msg, a1), ap));
        T.ok("ecdh symmetric", java.util.Arrays.equals(Secp256k1.ecdh(a1, bp), Secp256k1.ecdh(b1, ap)));
        byte[] ck = Nip44.conversationKey(a1, bp), ck2 = Nip44.conversationKey(b1, ap);
        String enc = Nip44.encrypt("hello مرحبا 😀", ck);
        T.eq("nip44 roundtrip", "hello مرحبا 😀", Nip44.decrypt(enc, ck2));
        T.eq("nip44 wrong key", null, Nip44.decrypt(enc, Nip44.conversationKey(Secp256k1.newSecret(), bp)));
        char[] flip = enc.toCharArray();
        flip[60] = flip[60] == 'A' ? 'B' : 'A';
        T.eq("nip44 tamper", null, Nip44.decrypt(new String(flip), ck2));

        // NIP-44 official padding table
        int[][] pads = {{1, 32}, {16, 32}, {32, 32}, {33, 64}, {37, 64}, {64, 64}, {65, 96}, {100, 128}, {111, 128}, {200, 224},
                {250, 256}, {320, 320}, {383, 384}, {384, 384}, {400, 448}, {500, 512}, {512, 512}, {515, 640}, {700, 768},
                {800, 896}, {900, 1024}, {1020, 1024}, {65535, 65536}};
        for (int[] p : pads) {
            String s = "a".repeat(p[0]);
            String e = Nip44.encrypt(s, ck);
            int raw = java.util.Base64.getDecoder().decode(e).length;
            T.eq("pad " + p[0], 1 + 32 + 2 + p[1] + 32, raw);
        }

        // symmetric blobs
        byte[] key = Nip44.newKey();
        byte[] blob = Nip44.seal(key, "secret bytes".getBytes());
        T.eq("seal/open", "secret bytes", new String(Nip44.open(key, blob)));
        blob[20] ^= 1;
        T.eq("open tampered", null, Nip44.open(key, blob));

        // NIP-19 example
        String hex = "7e7e9c42a91bfef19fa929e5fda1b72e0ebc1a4c1141673e2794234d86addf4e";
        String npub = "npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg";
        T.eq("npub encode", npub, Bech32.encode("npub", h(hex)));
        T.eq("npub decode", hex, x(Bech32.decode("npub", npub)));
        T.eq("npub bad checksum", null, Bech32.decode("npub", npub.substring(0, npub.length() - 1) + "q"));

        // JSON
        Object o = Json.parse("{\"a\":[1,2.5,\"x\\n\\u00e9\"],\"b\":{\"c\":true,\"d\":null}}");
        T.eq("json", "{\"a\":[1,2.5,\"x\\né\"],\"b\":{\"c\":true,\"d\":null}}", Json.write(o));
        T.eq("json bad", null, Json.parse("{\"a\":"));
        T.eq("json num", 7L, Json.num(Json.obj(Json.parse("{\"n\":7}")), "n", 0));
        T.done("CryptoTests");
    }
}
