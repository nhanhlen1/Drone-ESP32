param(
    [Parameter(Mandatory=$true)][string]$InputZip,
    [string]$OutputZip
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$inputPath = (Resolve-Path -LiteralPath $InputZip).Path
if (-not $OutputZip) { $OutputZip = Join-Path (Split-Path $inputPath) 'GG_AI_SOURCE_ONLY.zip' }
$outputPath = [System.IO.Path]::GetFullPath($OutputZip)
if (Test-Path -LiteralPath $outputPath) { throw "Output already exists; choose another name: $outputPath" }
$inputArchive = [System.IO.Compression.ZipFile]::OpenRead($inputPath)
$outputStream = $null
$outputArchive = $null
$count = 0
$skipped = 0
[long]$total = 0
try {
    $outputStream = [System.IO.File]::Open($outputPath, [System.IO.FileMode]::CreateNew)
    $outputArchive = [System.IO.Compression.ZipArchive]::new($outputStream, [System.IO.Compression.ZipArchiveMode]::Create, $true)
    foreach ($entry in $inputArchive.Entries) {
        $name = $entry.FullName.Replace([char]92,[char]47)
        $parts = $name.Split('/')
        if ($name.StartsWith('/') -or $name.Contains(':') -or ($parts -contains '..')) { throw "Unsafe archive path: $name" }
        $excludedFolder = @($parts | Where-Object { $_ -in @('build','.gradle','.kotlin','node_modules','.git','.idea','dist') }).Count -gt 0
        $excludedFile = $name -match '(?i)\.(apk|aab|zip|jks|keystore)$' -or $entry.Name -like '.env*' -or $entry.Name -eq 'local.properties'
        if ($excludedFolder -or $excludedFile -or $name.EndsWith('/')) { $skipped++; continue }
        if ($entry.Length -gt 8MB -or $total + $entry.Length -gt 128MB -or $count -ge 100000) { throw "Source exceeds preparation limits at: $name" }
        $newEntry = $outputArchive.CreateEntry($name, [System.IO.Compression.CompressionLevel]::Optimal)
        $source = $entry.Open()
        $destination = $newEntry.Open()
        try { $source.CopyTo($destination) } finally { $destination.Dispose(); $source.Dispose() }
        $total += $entry.Length
        $count++
    }
} catch {
    if ($outputArchive) { $outputArchive.Dispose(); $outputArchive = $null }
    if ($outputStream) { $outputStream.Dispose(); $outputStream = $null }
    if (Test-Path -LiteralPath $outputPath) { Remove-Item -LiteralPath $outputPath }
    throw
} finally {
    if ($outputArchive) { $outputArchive.Dispose() }
    if ($outputStream) { $outputStream.Dispose() }
    $inputArchive.Dispose()
}
$size = (Get-Item -LiteralPath $outputPath).Length
Write-Output "Created $outputPath ($count files; $skipped entries excluded; $size bytes). Original ZIP unchanged."
if ($size -gt 32MB) { throw 'Still above transfer limit: send only app/src, Gradle files and Markdown documents.' }
Get-FileHash -Algorithm SHA256 -LiteralPath $outputPath
