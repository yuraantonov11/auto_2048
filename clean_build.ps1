# Free disk space by deleting Flutter build intermediates
$base = "C:\Users\yuraa\WebstormProjects\auto_2048\build\app\intermediates"

if (Test-Path $base) {
    Write-Host "Deleting intermediates..."
    Get-ChildItem $base -Recurse -File | ForEach-Object { Remove-Item $_.FullName -Force }
    Get-ChildItem $base -Recurse -Directory | Sort-Object { $_.FullName.Length } -Descending | ForEach-Object { Remove-Item $_.FullName -Force -ErrorAction SilentlyContinue }
    Write-Host "Done cleaning intermediates"
}

# Also delete stripped_native_libs
$stripped = "C:\Users\yuraa\WebstormProjects\auto_2048\build\app\intermediates\stripped_native_libs"
if (Test-Path $stripped) {
    Remove-Item $stripped -Recurse -Force
    Write-Host "Deleted stripped_native_libs"
}

Write-Host "Freeing APK copy destination..."
$destDir = "C:\Users\yuraa\WebstormProjects\auto_2048\build\app\outputs\flutter-apk"
if (Test-Path $destDir) {
    Remove-Item "$destDir\*" -Force -ErrorAction SilentlyContinue
}

Write-Host "Copying APK..."
New-Item -ItemType Directory -Force -Path $destDir | Out-Null
Copy-Item "C:\Users\yuraa\WebstormProjects\auto_2048\build\app\outputs\apk\debug\app-debug.apk" "$destDir\app-debug.apk" -Force
Write-Host "Done!"
