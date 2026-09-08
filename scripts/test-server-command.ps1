#requires -Version 7.0
[CmdletBinding()]
param([Parameter(Mandatory, Position = 0)][string]$Command)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$properties = Get-Content -LiteralPath "$repo/.local/server/server.properties"
$password = ($properties | Where-Object { $_ -match '^rcon.password=' }) -replace '^rcon.password=', ''
$port = [int](($properties | Where-Object { $_ -match '^rcon.port=' }) -replace '^rcon.port=', '')
if (!$password) { throw 'Local RCON is not configured; run setup-test-runtime.ps1.' }
$client = [Net.Sockets.TcpClient]::new()
try {
    $client.Connect('127.0.0.1',$port)
    $stream = $client.GetStream()
    $stream.ReadTimeout = 10000
    $stream.WriteTimeout = 10000
    $writer = [IO.BinaryWriter]::new($stream,[Text.Encoding]::UTF8,$true)
    $reader = [IO.BinaryReader]::new($stream,[Text.Encoding]::UTF8,$true)
    function Send-Packet([int]$Id,[int]$Type,[string]$Text) {
        $data = [Text.Encoding]::UTF8.GetBytes($Text)
        # Emit one complete frame; Minecraft's RCON implementation rejects partial TCP reads.
        $frame = [IO.MemoryStream]::new()
        $packet = [IO.BinaryWriter]::new($frame)
        try {
            $packet.Write([int]($data.Length + 10))
            $packet.Write($Id)
            $packet.Write($Type)
            $packet.Write($data)
            $packet.Write([byte]0)
            $packet.Write([byte]0)
            $packet.Flush()
            $bytes = $frame.ToArray()
            $stream.Write($bytes, 0, $bytes.Length)
            $stream.Flush()
        } finally { $packet.Dispose(); $frame.Dispose() }
    }
    function Read-Packet {
        $length = $reader.ReadInt32()
        if ($length -lt 10 -or $length -gt 1048576) { throw "Invalid RCON response length $length" }
        $id = $reader.ReadInt32()
        $type = $reader.ReadInt32()
        $body = $reader.ReadBytes($length - 8)
        if ($body.Length -ne $length - 8) { throw 'RCON response ended early.' }
        return @{ id = $id; type = $type; text = [Text.Encoding]::UTF8.GetString($body,0,$body.Length-2) }
    }
    Send-Packet 1 3 $password
    $auth = Read-Packet
    if ($auth.id -eq -1) { throw 'Local RCON authentication failed.' }
    if ($auth.type -eq 0) { $auth = Read-Packet }
    if ($auth.id -ne 1 -or $auth.type -ne 2) { throw 'Unexpected local RCON authentication response.' }
    Send-Packet 2 2 $Command
    $response = Read-Packet
    if ($response.id -ne 2) { throw 'Unexpected local RCON command response.' }
    Write-Output $response.text
} finally { $client.Dispose() }
