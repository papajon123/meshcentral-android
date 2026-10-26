# Synthetic certificate fixtures

## Invalid issuer key usage fixture

`invalid-key-usage-chain.crt` contains only two synthetic public X.509 certificates:
the `mesh.example` leaf followed by its `Invalid Usage Test CA` issuer. Both are
valid from 2020-01-01 through 2100-01-01. Their SHA-256/RSA signatures are valid.
The issuer has critical `basicConstraints: CA=true, pathLen=0`, but its critical
key usage permits **digitalSignature only**, without `keyCertSign`.

Generated with .NET `CertificateRequest` and ephemeral in-memory RSA keys. The
keys were not exported or saved. `ServerTrustTest` independently verifies the
leaf signature and CA constraint before asserting rejection for key usage.

## Key strength, signature and server purpose fixtures

The `weak-rsa`, `strong-rsa`, `weak-ec`, `strong-ec`, `md5-leaf`, `sha1-leaf`,
`client-auth-only` and `server-auth` certificates are public, self-signed fixtures
for `mesh.example`, generated on 2026-10-26 with ten-year validity. No private keys
are retained. They exercise RSA-1024/2048, EC P-224/P-256, MD5/SHA-1 signatures and
client-only/server-and-client extended key usage. Other signatures use SHA-256.
Tests using these files must be renewed before their validity expires in 2036.

`weak-ec-strong-issuer-chain.crt` contains a P-224 leaf signed by a strong RSA-2048
CA (2020–2036), generated with .NET and an ephemeral in-memory issuer key. This
isolates leaf-key strength from JDK providers that cannot verify P-224 signatures.

Other TLS tests generate short-lived certificates at runtime for expiry, path
building, key rotation and actual loopback handshakes.
