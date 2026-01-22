package libbox

import (
	"log"
	"strings"
	"sync"
	"sync/atomic"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/conntrack"
	"github.com/sagernet/sing-box/protocol/group"
)

// ============================================================================
// KunBox BoxWrapper Extension - 节点切换/电源管理/流量统计
// 提供 BoxWrapper 类型封装 BoxService，支持热切换节点等功能
// Version: 1.2.0
// Compatible with: sing-box v1.10.0 - v1.12.x
//
// 依赖的官方包 (稳定):
//   - github.com/sagernet/sing-box/adapter
//   - github.com/sagernet/sing-box/protocol/group
//   - github.com/sagernet/sing-box/common/conntrack
// ============================================================================

const ExtensionVersion = "1.2.0"

var (
	globalWrapper     *BoxWrapper
	globalWrapperLock sync.RWMutex
)

// BoxWrapper wraps BoxService to provide additional functionality
type BoxWrapper struct {
	service      *BoxService
	isPaused     atomic.Bool
	uploadTotal  atomic.Int64
	downloadTotal atomic.Int64
}

// WrapBoxService creates a BoxWrapper from a BoxService
func WrapBoxService(service *BoxService) *BoxWrapper {
	if service == nil {
		return nil
	}
	wrapper := &BoxWrapper{
		service: service,
	}

	globalWrapperLock.Lock()
	globalWrapper = wrapper
	globalWrapperLock.Unlock()

	log.Println("[KunBox BoxWrapper] Created and set as global wrapper")
	return wrapper
}

// ClearGlobalWrapper clears the global wrapper reference
func ClearGlobalWrapper() {
	globalWrapperLock.Lock()
	globalWrapper = nil
	globalWrapperLock.Unlock()
	log.Println("[KunBox BoxWrapper] Global wrapper cleared")
}

// GetGlobalWrapper returns the current global wrapper
func GetGlobalWrapper() *BoxWrapper {
	globalWrapperLock.RLock()
	defer globalWrapperLock.RUnlock()
	return globalWrapper
}

// GetExtensionVersion returns the KunBox extension version
func GetExtensionVersion() string {
	return ExtensionVersion
}

// ==================== Selector Operations ====================

// SelectOutbound switches to the specified outbound in the selector
func (w *BoxWrapper) SelectOutbound(tag string) error {
	if w == nil || w.service == nil || w.service.instance == nil {
		log.Println("[KunBox BoxWrapper] SelectOutbound: service not available")
		return nil
	}

	outbounds := w.service.instance.Outbound().Outbounds()
	for _, outbound := range outbounds {
		if selector, ok := outbound.(adapter.OutboundGroup); ok {
			if selectorGroup, isSelector := selector.(*group.Selector); isSelector {
				if selectorGroup.SelectOutbound(tag) {
					log.Printf("[KunBox BoxWrapper] SelectOutbound: switched to %s", tag)
					return nil
				}
			}
		}
	}

	log.Printf("[KunBox BoxWrapper] SelectOutbound: tag %s not found in any selector", tag)
	return nil
}

// SelectedOutbound returns the currently selected outbound tag
func (w *BoxWrapper) SelectedOutbound() string {
	if w == nil || w.service == nil || w.service.instance == nil {
		return ""
	}

	outbounds := w.service.instance.Outbound().Outbounds()
	for _, outbound := range outbounds {
		if selector, ok := outbound.(adapter.OutboundGroup); ok {
			if selectorGroup, isSelector := selector.(*group.Selector); isSelector {
				selected := selectorGroup.Now()
				if selected != "" {
					return selected
				}
			}
		}
	}
	return ""
}

// ListOutboundsString returns all outbound tags as newline-separated string
func (w *BoxWrapper) ListOutboundsString() string {
	if w == nil || w.service == nil || w.service.instance == nil {
		return ""
	}

	var tags []string
	outbounds := w.service.instance.Outbound().Outbounds()
	for _, outbound := range outbounds {
		tags = append(tags, outbound.Tag())
	}
	return strings.Join(tags, "\n")
}

// HasSelector checks if there's a selector-type outbound
func (w *BoxWrapper) HasSelector() bool {
	if w == nil || w.service == nil || w.service.instance == nil {
		return false
	}

	outbounds := w.service.instance.Outbound().Outbounds()
	for _, outbound := range outbounds {
		if _, ok := outbound.(*group.Selector); ok {
			return true
		}
	}
	return false
}

// ==================== Power Management ====================

// Pause pauses the service (power saving mode)
func (w *BoxWrapper) Pause() {
	if w == nil || w.service == nil {
		return
	}
	w.isPaused.Store(true)
	// Note: box.Box doesn't have Pause method, we just track state
	log.Println("[KunBox BoxWrapper] Paused (state tracked)")
}

// Resume resumes the service from pause
func (w *BoxWrapper) Resume() {
	if w == nil || w.service == nil {
		return
	}
	w.isPaused.Store(false)
	// Note: box.Box doesn't have Wake method, we just track state
	log.Println("[KunBox BoxWrapper] Resumed (state tracked)")
}

// IsPaused returns whether the service is paused
func (w *BoxWrapper) IsPaused() bool {
	if w == nil {
		return false
	}
	return w.isPaused.Load()
}

// ==================== Traffic Statistics ====================

// UploadTotal returns the total uploaded bytes
// Returns -1 to indicate kernel-level traffic tracking is not available,
// triggering fallback to Android TrafficStats API
func (w *BoxWrapper) UploadTotal() int64 {
	// Kernel-level traffic tracking not implemented in sing-box
	// Return -1 to trigger fallback to TrafficStats in Kotlin layer
	return -1
}

// DownloadTotal returns the total downloaded bytes
// Returns -1 to indicate kernel-level traffic tracking is not available,
// triggering fallback to Android TrafficStats API
func (w *BoxWrapper) DownloadTotal() int64 {
	// Kernel-level traffic tracking not implemented in sing-box
	// Return -1 to trigger fallback to TrafficStats in Kotlin layer
	return -1
}

// ResetTraffic resets the traffic counters
func (w *BoxWrapper) ResetTraffic() {
	if w == nil {
		return
	}
	w.uploadTotal.Store(0)
	w.downloadTotal.Store(0)
	log.Println("[KunBox BoxWrapper] Traffic reset")
}

// ==================== Connection Management ====================

// ResetAllConnections resets all active connections
func ResetAllConnections(system bool) {
	if system {
		conntrack.Close()
		log.Println("[KunBox BoxWrapper] Reset all connections (system)")
	} else {
		log.Println("[KunBox BoxWrapper] Reset connections (non-system) - no-op")
	}
}

// ==================== Helper Functions ====================

// ParseTags splits a newline-separated string into tags
func ParseTags(tagsStr string) []string {
	if tagsStr == "" {
		return nil
	}
	tags := strings.Split(tagsStr, "\n")
	var result []string
	for _, tag := range tags {
		tag = strings.TrimSpace(tag)
		if tag != "" {
			result = append(result, tag)
		}
	}
	return result
}
