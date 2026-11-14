package main

import (
"encoding/base64"
"flag"
"fmt"
"os"
"os/signal"
"strconv"
"strings"
"syscall"
"time"

soar "soar-mobile/soar"
)

type profile struct {
tunnelType string
zone       string
resolvers  string
publicKey  string
discovery  bool
country    string
tcpMode    string
list       string
candidates string
}

func parseProfile(link string) (profile, error) {
var p profile
link = strings.TrimSpace(link)

const scheme = "slipnet://"
if !strings.HasPrefix(strings.ToLower(link), scheme) {
return p, fmt.Errorf("expected plain slipnet:// profile")
}

payload := strings.TrimSpace(link[len(scheme):])
payload = strings.Join(strings.Fields(payload), "")

raw, err := base64.StdEncoding.DecodeString(payload)
if err != nil {
for len(payload)%4 != 0 {
payload += "="
}
raw, err = base64.StdEncoding.DecodeString(payload)
if err != nil {
return p, fmt.Errorf("invalid profile base64: %w", err)
}
}

fields := strings.Split(string(raw), "|")
if len(fields) < 12 {
return p, fmt.Errorf("profile has %d fields; at least 12 required", len(fields))
}

at := func(i int) string {
if i < len(fields) {
return strings.TrimSpace(fields[i])
}
return ""
}

p.tunnelType = strings.ToLower(at(1))
p.zone = at(3)
p.resolvers = at(4)
p.publicKey = at(11)

if len(fields) > 79 {
p.discovery = at(79) == "1"
}
if len(fields) > 80 {
p.country = at(80)
}
if len(fields) > 81 && at(81) != "" {
p.tcpMode = strings.ToLower(at(81))
} else {
p.tcpMode = "auto"
}
if len(fields) > 82 {
p.list = at(82)
}
if len(fields) > 83 {
p.candidates = at(83)
}

if p.tunnelType != "soar" {
if p.tunnelType == "soar_ssh" {
return p, fmt.Errorf("soar_ssh requires the SSH chain layer and is not handled by this standalone runtime")
}
return p, fmt.Errorf("unsupported tunnel type for Soar runtime: %s", p.tunnelType)
}
if p.zone == "" {
return p, fmt.Errorf("Soar tunnel zone is empty")
}
if p.publicKey == "" {
return p, fmt.Errorf("Soar Ed25519 public key (field 11) is empty")
}
if p.resolvers == "" && !p.discovery {
return p, fmt.Errorf("no resolvers configured and resolver discovery is disabled")
}

return p, nil
}

func main() {
host := flag.String("host", "127.0.0.1", "SOCKS5 listen host")
port := flag.Int("port", 10881, "SOCKS5 listen port")
upstream := flag.String("upstream-socks", "", "optional upstream SOCKS5 host:port")
upUser := flag.String("upstream-user", "", "upstream SOCKS5 username")
upPass := flag.String("upstream-pass", "", "upstream SOCKS5 password")
verbose := flag.Bool("verbose", false, "enable verbose Soar logs")
flag.Parse()

if flag.NArg() != 1 {
fmt.Fprintln(os.Stderr, "Usage: soar-wrapper.exe [options] slipnet://PROFILE")
os.Exit(2)
}
if *port < 1 || *port > 65535 {
fmt.Fprintln(os.Stderr, "Invalid listen port")
os.Exit(2)
}

p, err := parseProfile(flag.Arg(0))
if err != nil {
fmt.Fprintln(os.Stderr, "SOAR_CONFIG_ERROR:", err)
os.Exit(2)
}

listen := *host + ":" + strconv.Itoa(*port)
client, err := soar.NewClient(p.resolvers, p.zone, p.publicKey, listen)
if err != nil {
fmt.Fprintln(os.Stderr, "SOAR_INIT_ERROR:", err)
os.Exit(1)
}

client.SetDiscovery(p.discovery, p.country)
if p.discovery && p.candidates != "" {
client.SetDiscoveryCandidates(p.candidates)
}
if p.tcpMode != "" {
client.SetTCPMode(p.tcpMode)
}
client.SetVerbose(*verbose)

if *upstream != "" {
client.SetUpstreamSocks5(*upstream, *upUser, *upPass)
}

if err := client.Start(); err != nil {
fmt.Fprintln(os.Stderr, "SOAR_START_ERROR:", err)
os.Exit(1)
}
defer client.Stop()

if !client.IsRunning() {
		fmt.Fprintln(os.Stderr, "SOAR_START_ERROR: client did not enter running state")
		client.Stop()
		os.Exit(1)
	}

	// Wait for the authenticated Soar handshake, not only the local listener.
	handshakeDeadline := time.Now().Add(35 * time.Second)
	for !client.IsEstablished() {
		if !client.IsRunning() {
			fmt.Fprintln(os.Stderr, "SOAR_RUNTIME_EXITED: listener stopped during handshake")
			client.Stop()
			os.Exit(1)
		}
		if time.Now().After(handshakeDeadline) {
			fmt.Fprintln(os.Stderr, "SOAR_HANDSHAKE_FAILED: no authenticated session after 35 seconds; check zone, public key and resolvers")
			client.Stop()
			os.Exit(1)
		}
		time.Sleep(200 * time.Millisecond)
	}

	fmt.Printf("SOAR_READY %s\\n", listen)
	fmt.Printf("SOAR_ESTABLISHED verified_paths=%d\\n", client.VerifiedPathCount())

	sig := make(chan os.Signal, 1)
signal.Notify(sig, os.Interrupt, syscall.SIGTERM)

ticker := time.NewTicker(time.Second)
defer ticker.Stop()

for {
select {
case <-sig:
fmt.Println("SOAR_STOPPING")
return
case <-ticker.C:
if !client.IsRunning() {
fmt.Fprintln(os.Stderr, "SOAR_RUNTIME_EXITED")
return
}
}
}
}
