// Package tsembed is a thin wrapper around tailscale.com/tsnet, existing
// solely because gomobile's gobind tool cannot generate Java/Kotlin bindings
// directly against tsnet.Server: gobind only supports methods that return
// zero or one values plus an optional error, and several of tsnet.Server's
// own methods (Loopback in particular) don't fit that shape. Every exported
// method here is deliberately gobind-compatible; anything with a richer Go
// signature (Loopback's three-value return, raw net.Conn, etc.) is called
// from plain Go inside this package and never crosses the binding boundary
// directly.
//
// The embedding pattern used here - tsnet's own Loopback() SOCKS5 proxy,
// rather than trying to bridge Dial()/net.Conn into Java - is tsnet's
// intended mechanism for exactly this "give a non-Go program tailnet
// access" use case (see Loopback's doc comment in tsnet.Server). Kotlin
// talks to the resulting loopback address as a plain SOCKS5 proxy via
// java.net.Proxy, so no Go network types ever need a Java binding at all.
package tsembed

import (
	"context"
	"errors"
	"os"
	"time"

	"tailscale.com/tsnet"
)

func init() {
	// Confirmed live: without this, tsnet.Server.Start fails outright on
	// Android with "route ip+net: netlinkrib: permission denied" - Android's
	// SELinux policy blocks unprivileged apps from netlink route-table
	// queries, and that error comes from logpolicy.NewLogtailTransport (log
	// upload setup) calling net.Interfaces() internally, not from any code
	// path this app actually needs - see tailscale/tailscale#17311 (open,
	// unresolved upstream as of this writing) vs tailscale/tailscale#9836
	// (a similar but different call path, already fixed via Android build
	// tags in wgengine/router - those don't cover this one).
	// TS_NO_LOGS_NO_SUPPORT is Tailscale's own documented opt-out of client
	// log uploads, which skips constructing that transport entirely -
	// desirable on its own merits for this project anyway (an embedded
	// tailnet client on a kid's device shouldn't be uploading operational
	// logs to Tailscale's servers), and happens to route around this bug as
	// a side effect. Must be set before any tsnet.Server method runs.
	os.Setenv("TS_NO_LOGS_NO_SUPPORT", "true")
}

// Client owns one embedded tailnet node. Not safe for concurrent use across
// goroutines/threads without external synchronization - callers should treat
// it as owned by a single Kotlin-side service instance.
type Client struct {
	srv          *tsnet.Server
	proxyAddr    string
	proxyCred    string
	localAPICred string
}

// New creates (but does not yet connect) a client. stateDir must be a
// directory the app can write to (e.g. context.filesDir) - tsnet persists
// its node identity/keys there so the device doesn't need to re-authenticate
// with authKey on every restart.
func New(hostname, authKey, stateDir string) *Client {
	return &Client{
		srv: &tsnet.Server{
			Hostname:  hostname,
			AuthKey:   authKey,
			Dir:       stateDir,
			Ephemeral: false,
		},
	}
}

// Up connects to the tailnet and blocks until either connected or
// timeoutSeconds elapses. Returns this node's own Tailscale IPv4 address on
// success. Safe to call again after a failure (tsnet.Server.Start(), which
// this calls internally via Up, is idempotent).
func (c *Client) Up(timeoutSeconds int) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutSeconds)*time.Second)
	defer cancel()

	if _, err := c.srv.Up(ctx); err != nil {
		return "", err
	}
	ip4, _ := c.srv.TailscaleIPs()
	if !ip4.IsValid() {
		return "", errors.New("connected but no IPv4 address assigned yet")
	}
	return ip4.String(), nil
}

// StartProxy starts (or returns the already-running) local SOCKS5 proxy onto
// the tailnet, returning the loopback address to point a SOCKS5 client at
// (e.g. "127.0.0.1:1080"). Call ProxyUsername/ProxyPassword afterward for the
// credentials the proxy requires - tsnet.Server.Loopback returns three
// values plus an error, which gobind can't bind directly (see package doc),
// so this wraps it and caches the credentials as separate zero-argument
// getters instead.
func (c *Client) StartProxy() (string, error) {
	if c.proxyAddr != "" {
		return c.proxyAddr, nil
	}
	addr, proxyCred, localAPICred, err := c.srv.Loopback()
	if err != nil {
		return "", err
	}
	c.proxyAddr = addr
	c.proxyCred = proxyCred
	c.localAPICred = localAPICred
	return addr, nil
}

// ProxyCredential returns the credential tsnet's local SOCKS5 proxy requires
// for authentication (opaque from this package's perspective - passed
// through exactly as tsnet.Server.Loopback returned it). Empty until
// StartProxy has succeeded at least once. Exact format/usage against
// java.net.Authenticator to be confirmed once this is wired up client-side.
func (c *Client) ProxyCredential() string {
	return c.proxyCred
}

// Close shuts down the tailnet connection and proxy.
func (c *Client) Close() error {
	return c.srv.Close()
}
