// A minimal Go module whose sole purpose is giving `gomobile bind` (see
// .github/workflows/android.yml) a module context to resolve tailscale.com/tsnet
// from - this directory has no Go source of its own, nothing here runs standalone.
module kidslauncher-mdm-mobile

go 1.23
