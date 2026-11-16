param(
    [Parameter(Mandatory = $true)]
    [string]$EclipsePlugins,
    [string]$Schedule,
    [string]$Supermarket,
    [string]$Output
)

$ErrorActionPreference = 'Stop'
$scriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }
if (!$Output) {
    $Output = Join-Path $scriptDir 'generated'
}
$workspace = Split-Path -Parent $scriptDir
if (!$Schedule) {
    $Schedule = Join-Path $workspace 'MealSchedule/model/MealSchedule.xmi'
}
if (!$Supermarket) {
    $Supermarket = Join-Path $workspace 'Supermarket/model/Supermarket.xmi'
}

$requiredBundles = @(
    'org.eclipse.acceleo.aql', 'org.eclipse.acceleo.query', 'org.eclipse.m2m.qvt.oml'
)
foreach ($bundle in $requiredBundles) {
    if (!(Get-ChildItem -LiteralPath $EclipsePlugins -Filter "${bundle}_*.jar" -File)) {
        throw "Missing $bundle in $EclipsePlugins. Point -EclipsePlugins to an Eclipse installation with Acceleo 4 and QVTo."
    }
}

$jarPattern = '^(org\.eclipse\.(acceleo\.(aql|query|annotations)|emf\.(common|ecore|ecore\.xmi)|m2m\.qvt\.oml(?:\.[^.]+)*|ocl(?:\.[^.]+)*|core\.(resources|filesystem|runtime|jobs|contenttype)|equinox\.(common|registry|preferences)|osgi)|org\.osgi\.service\.prefs|org\.antlr\.(antlr4-runtime|runtime)|com\.google\.(guava|gson)|lpg\.runtime\.java)_'
$jars = Get-ChildItem -LiteralPath $EclipsePlugins -Filter '*.jar' -File |
    Where-Object { $_.Name -match $jarPattern -and $_.Name -notmatch '\.source_' } |
    Sort-Object Name -Descending |
    Group-Object { $_.Name.Split('_')[0] } |
    ForEach-Object { $_.Group[0].FullName }
$classpath = ($jars + (Join-Path $scriptDir 'src')) -join [IO.Path]::PathSeparator

& java --class-path $classpath (Join-Path $scriptDir 'GenerateOutputs.java') $workspace $Output $Schedule $Supermarket
if ($LASTEXITCODE -ne 0) {
    throw "Generation failed with exit code $LASTEXITCODE."
}
Get-Item -LiteralPath @(
    (Join-Path $Output 'GeneratedShoppingList.xmi'),
    (Join-Path $Output 'weekly-menu.html'),
    (Join-Path $Output 'shopping-list.html')
)
