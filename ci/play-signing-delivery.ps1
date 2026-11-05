param(
    [Parameter(Mandatory)][ValidateSet('Create','Open','Cleanup')][string]$Action,
    [Parameter(Mandatory)][string]$StatePath,
    [string]$CertificatePath,
    [string]$EnvelopePath,
    [string]$OutputPath,
    [string]$ExpectedEnvelopeSha256,
    [string]$ExpectedZipSha256,
    [string]$ExpectedSignerSha256='5458084705359e0dfa9cbb83ed73a0d77c7758370cffe2a7d6b036e9275eeb2f'
)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Security.Cryptography.Pkcs

function Hash-Bytes([byte[]]$Bytes) {
    [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($Bytes)).ToLowerInvariant()
}
function Write-NewFile([string]$Path,[byte[]]$Bytes) {
    $stream=[IO.File]::Open($Path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try { $stream.Write($Bytes,0,$Bytes.Length) } finally { $stream.Dispose() }
}

if($Action -eq 'Create') {
    if(-not $CertificatePath -or (Test-Path -LiteralPath $StatePath) -or (Test-Path -LiteralPath $CertificatePath)) {
        throw 'New state and public certificate paths are required'
    }
    $name='MeshPilot.PlayTransfer.0.10.0.'+[guid]::NewGuid().ToString('N')
    $options=[Security.Cryptography.CngKeyCreationParameters]::new()
    $options.Provider=[Security.Cryptography.CngProvider]::MicrosoftSoftwareKeyStorageProvider
    $options.ExportPolicy=[Security.Cryptography.CngExportPolicies]::None
    $options.KeyUsage=[Security.Cryptography.CngKeyUsages]::Signing -bor [Security.Cryptography.CngKeyUsages]::Decryption
    $options.Parameters.Add([Security.Cryptography.CngProperty]::new('Length',[BitConverter]::GetBytes(3072),[Security.Cryptography.CngPropertyOptions]::None))
    $key=$null; $rsa=$null; $certificate=$null; $complete=$false
    try {
        $key=[Security.Cryptography.CngKey]::Create([Security.Cryptography.CngAlgorithm]::Rsa,$name,$options)
        if($key.ExportPolicy -ne [Security.Cryptography.CngExportPolicies]::None -or $key.IsMachineKey) { throw 'CNG policy mismatch' }
        $rsa=[Security.Cryptography.RSACng]::new($key)
        $request=[Security.Cryptography.X509Certificates.CertificateRequest]::new('CN=MeshPilot one-time Play signing delivery',$rsa,[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.RSASignaturePadding]::Pkcs1)
        $certificate=$request.CreateSelfSigned([DateTimeOffset]::UtcNow.AddMinutes(-5),[DateTimeOffset]::UtcNow.AddDays(7))
        $pem=[Text.Encoding]::UTF8.GetBytes(($certificate.ExportCertificatePem() -replace "`r",''))
        Write-NewFile $CertificatePath $pem
        $state=@{keyName=$name;certificatePath=[IO.Path]::GetFullPath($CertificatePath);certificateFileSha256=(Hash-Bytes $pem);createdUtc=[DateTimeOffset]::UtcNow.ToString('o')}
        Write-NewFile $StatePath ([Text.Encoding]::UTF8.GetBytes(($state|ConvertTo-Json)))
        $complete=$true
        Write-Output ('Non-exportable CNG delivery key created; public certificate SHA-256: '+$state.certificateFileSha256)
    } finally {
        if($certificate){$certificate.Dispose()}; if($rsa){$rsa.Dispose()}
        if($key){if(-not $complete){$key.Delete()};$key.Dispose()}
    }
    exit 0
}

$state=Get-Content -LiteralPath $StatePath -Raw -Encoding utf8 | ConvertFrom-Json
if($state.keyName -notmatch '^MeshPilot\.PlayTransfer\.0\.10\.0\.[a-f0-9]{32}$') { throw 'Unexpected delivery-key identity' }
$publicBytes=[IO.File]::ReadAllBytes($state.certificatePath)
if((Hash-Bytes $publicBytes) -ne $state.certificateFileSha256) { throw 'Delivery certificate changed' }
$certificate=[Security.Cryptography.X509Certificates.X509Certificate2]::CreateFromPem([Text.Encoding]::UTF8.GetString($publicBytes))
$key=[Security.Cryptography.CngKey]::Open($state.keyName,[Security.Cryptography.CngProvider]::MicrosoftSoftwareKeyStorageProvider)
$rsa=[Security.Cryptography.RSACng]::new($key)
try {
    if($key.ExportPolicy -ne [Security.Cryptography.CngExportPolicies]::None -or $key.IsMachineKey) { throw 'Unexpected key policy' }
    $publicRsa=[Security.Cryptography.X509Certificates.RSACertificateExtensions]::GetRSAPublicKey($certificate)
    try {
        if([Convert]::ToBase64String($publicRsa.ExportSubjectPublicKeyInfo()) -cne [Convert]::ToBase64String($rsa.ExportSubjectPublicKeyInfo())) { throw 'Delivery key does not match its certificate' }
    } finally { $publicRsa.Dispose() }
    if($Action -eq 'Cleanup') { $key.Delete(); Write-Output 'Owned CNG delivery key removed'; exit 0 }
    foreach($hash in @($ExpectedEnvelopeSha256,$ExpectedZipSha256,$ExpectedSignerSha256)) {
        if($hash -cnotmatch '^[0-9a-f]{64}$') { throw 'Trusted expected hashes are required' }
    }
    if(-not $OutputPath -or (Test-Path -LiteralPath $OutputPath)) { throw 'Output must be a new file' }
    if((Get-Item -LiteralPath $EnvelopePath).Length -gt 1048576) { throw 'Envelope exceeds size bound' }
    $encrypted=[IO.File]::ReadAllBytes($EnvelopePath)
    # This hash must be obtained from the authenticated, verified CI run before decoding CBC.
    if((Hash-Bytes $encrypted) -cne $ExpectedEnvelopeSha256) { throw 'Envelope integrity check failed' }
    $cms=[Security.Cryptography.Pkcs.EnvelopedCms]::new()
    $cms.Decode($encrypted)
    if($cms.RecipientInfos.Count -ne 1 -or $cms.ContentEncryptionAlgorithm.Oid.Value -ne '2.16.840.1.101.3.4.1.42' -or $cms.RecipientInfos[0].KeyEncryptionAlgorithm.Oid.Value -ne '1.2.840.113549.1.1.7') { throw 'Unexpected CMS profile' }
    $cms.Decrypt($cms.RecipientInfos[0],$rsa)
    $plain=$cms.ContentInfo.Content
    if($plain.Length -gt 131072 -or (Hash-Bytes $plain) -cne $ExpectedZipSha256) { throw 'Inner ZIP integrity check failed' }
    $memory=[IO.MemoryStream]::new($plain,$false)
    $zip=[IO.Compression.ZipArchive]::new($memory,[IO.Compression.ZipArchiveMode]::Read)
    try {
        $names=@($zip.Entries|ForEach-Object FullName|Sort-Object)
        if($names.Count -ne 2 -or $names[0] -ne 'certificate.pem' -or $names[1] -ne 'encryptedPrivateKey') { throw 'Unexpected PEPK ZIP structure' }
        $entry=$zip.GetEntry('certificate.pem')
        if($entry.Length -gt 16384) { throw 'Certificate exceeds size bound' }
        $reader=[IO.StreamReader]::new($entry.Open())
        try { $pem=$reader.ReadToEnd() } finally { $reader.Dispose() }
        $appCertificate=[Security.Cryptography.X509Certificates.X509Certificate2]::CreateFromPem($pem)
        try { if((Hash-Bytes $appCertificate.RawData) -cne $ExpectedSignerSha256) { throw 'Wrong application signing identity' } } finally { $appCertificate.Dispose() }
    } finally { $zip.Dispose();$memory.Dispose() }
    Write-NewFile $OutputPath $plain
    Write-Output 'Delivery envelope opened; Google-encrypted ZIP and application certificate verified'
} finally { $rsa.Dispose();$key.Dispose();$certificate.Dispose() }
