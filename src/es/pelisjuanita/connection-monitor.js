/**
 * Connection Monitor - Monitor internet connection status
 * Display notifications when connection is lost/restored
 */

(function() {
    'use strict';

    // Configuration
    const CONFIG = {
        offlinePageUrl: '/offline.html',
        checkInterval: 5000, // Check every 5 seconds when offline
        notificationDuration: 5000, // Show notification for 5 seconds
        excludePages: ['/offline.html', '/public/offline.html'] // Don't redirect from these pages
    };

    // State
    let wasOnline = navigator.onLine;
    let checkIntervalId = null;
    let notificationTimeout = null;

    /**
     * Check if current page should be excluded from offline redirect
     */
    function isExcludedPage() {
        const currentPath = window.location.pathname;
        return CONFIG.excludePages.some(page => currentPath.includes(page));
    }

    /**
     * Show notification toast
     */
    function showNotification(message, type = 'info') {
        // Remove existing notification
        const existing = document.getElementById('connection-notification');
        if (existing) {
            existing.remove();
        }

        // Create notification element
        const notification = document.createElement('div');
        notification.id = 'connection-notification';
        notification.className = `connection-notification connection-notification-${type}`;
        
        const icon = type === 'online' ? '✓' : type === 'offline' ? '📡' : 'ℹ️';
        
        notification.innerHTML = `
            <div class="connection-notification-content">
                <span class="connection-notification-icon">${icon}</span>
                <span class="connection-notification-message">${message}</span>
            </div>
        `;

        // Add styles if not already present
        if (!document.getElementById('connection-notification-styles')) {
            const style = document.createElement('style');
            style.id = 'connection-notification-styles';
            style.textContent = `
                .connection-notification {
                    position: fixed;
                    top: 20px;
                    right: 20px;
                    padding: 16px 24px;
                    border-radius: 12px;
                    box-shadow: 0 4px 20px rgba(0, 0, 0, 0.3);
                    z-index: 10000;
                    animation: slideIn 0.3s ease-out;
                    backdrop-filter: blur(10px);
                    max-width: 400px;
                    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                }

                .connection-notification-online {
                    background: linear-gradient(135deg, #10b981 0%, #059669 100%);
                    color: white;
                }

                .connection-notification-offline {
                    background: linear-gradient(135deg, #ef4444 0%, #dc2626 100%);
                    color: white;
                }

                .connection-notification-info {
                    background: linear-gradient(135deg, #3b82f6 0%, #2563eb 100%);
                    color: white;
                }

                .connection-notification-content {
                    display: flex;
                    align-items: center;
                    gap: 12px;
                }

                .connection-notification-icon {
                    font-size: 24px;
                    line-height: 1;
                }

                .connection-notification-message {
                    font-size: 14px;
                    font-weight: 500;
                    line-height: 1.4;
                }

                @keyframes slideIn {
                    from {
                        transform: translateX(100%);
                        opacity: 0;
                    }
                    to {
                        transform: translateX(0);
                        opacity: 1;
                    }
                }

                @keyframes slideOut {
                    from {
                        transform: translateX(0);
                        opacity: 1;
                    }
                    to {
                        transform: translateX(100%);
                        opacity: 0;
                    }
                }

                @media (max-width: 768px) {
                    .connection-notification {
                        left: 20px;
                        right: 20px;
                        top: auto;
                        bottom: 20px;
                        max-width: none;
                    }
                }
            `;
            document.head.appendChild(style);
        }

        document.body.appendChild(notification);

        // Auto remove after duration
        if (notificationTimeout) {
            clearTimeout(notificationTimeout);
        }

        notificationTimeout = setTimeout(() => {
            notification.style.animation = 'slideOut 0.3s ease-out forwards';
            setTimeout(() => {
                if (notification.parentNode) {
                    notification.remove();
                }
            }, 300);
        }, CONFIG.notificationDuration);
    }

    /**
     * Handle going offline
     */
    function handleOffline() {
        // Show notification
        showNotification('You are offline', 'offline');

        // Start checking for connection
        if (!checkIntervalId) {
            checkIntervalId = setInterval(checkConnection, CONFIG.checkInterval);
        }
    }

    /**
     * Handle coming back online
     */
    function handleOnline() {
        // Show notification
        showNotification('You are back online!', 'online');

        // Stop checking for connection
        if (checkIntervalId) {
            clearInterval(checkIntervalId);
            checkIntervalId = null;
        }

        // If we're on the offline page, redirect back to where we came from
        if (isExcludedPage()) {
            try {
                const redirectUrl = sessionStorage.getItem('offlineRedirectUrl');
                if (redirectUrl) {
                    sessionStorage.removeItem('offlineRedirectUrl');
                    setTimeout(() => {
                        window.location.href = redirectUrl;
                    }, 1500);
                } else {
                    setTimeout(() => {
                        window.location.href = '/';
                    }, 1500);
                }
            } catch (e) {
                // Could not get redirect URL
                
            }
        }
    }

    /**
     * Check connection status
     */
    function checkConnection() {
        const isOnline = navigator.onLine;

        if (isOnline !== wasOnline) {
            if (isOnline) {
                handleOnline();
            } else {
                handleOffline();
            }
            wasOnline = isOnline;
        }
    }

    /**
     * Verify actual connectivity (not just browser status)
     */
    async function verifyConnectivity() {
        try {
            // Try to fetch a small resource with cache-busting
            const response = await fetch('/favicon.ico?timestamp=' + Date.now(), {
                method: 'HEAD',
                cache: 'no-cache'
            });
            return response.ok;
        } catch (error) {
            return false;
        }
    }

    /**
     * Enhanced connection check
     */
    async function enhancedConnectionCheck() {
        const browserOnline = navigator.onLine;
        
        if (browserOnline) {
            // Browser says online, but verify actual connectivity
            const actuallyOnline = await verifyConnectivity();
            
            if (!actuallyOnline && wasOnline) {
                // Browser says online but we can't reach server
                handleOffline();
                wasOnline = false;
            } else if (actuallyOnline && !wasOnline) {
                handleOnline();
                wasOnline = true;
            }
        } else {
            // Browser says offline
            if (wasOnline) {
                handleOffline();
                wasOnline = false;
            }
        }
    }

    /**
     * Initialize connection monitor
     */
    function init() {
        // Listen for online/offline events
        window.addEventListener('online', () => {
            enhancedConnectionCheck();
        });

        window.addEventListener('offline', () => {
            enhancedConnectionCheck();
        });

        // Check connection on page visibility change
        document.addEventListener('visibilitychange', () => {
            if (!document.hidden) {
                enhancedConnectionCheck();
            }
        });

        // Initial check
        if (!navigator.onLine && !isExcludedPage()) {
            handleOffline();
        }

        // Periodic connectivity verification (every 30 seconds when online)
        setInterval(() => {
            if (navigator.onLine) {
                enhancedConnectionCheck();
            }
        }, 30000);
    }

    // Initialize when DOM is ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

    // Export functions for manual control if needed
    window.ConnectionMonitor = {
        check: enhancedConnectionCheck,
        isOnline: () => navigator.onLine,
        showNotification: showNotification
    };

})();

