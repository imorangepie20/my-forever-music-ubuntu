import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig(({ mode }) => {
    const env = loadEnv(mode, process.cwd(), '')
    const publicHost = env.VITE_PUBLIC_HOST?.trim() || 'imapplepie20.tplinkdns.com'
    const hmrProtocol = env.VITE_HMR_PROTOCOL?.trim() || undefined
    const hmrClientPort = env.VITE_HMR_CLIENT_PORT ? Number(env.VITE_HMR_CLIENT_PORT) : undefined
    const hmrDisabled = process.env.PLAYWRIGHT_E2E === 'true'

    return {
        plugins: [react()],
        resolve: {
            alias: {
                '@': fileURLToPath(new URL('./src', import.meta.url)),
            },
        },
        build: {
            target: 'esnext',
        },
        esbuild: {
            target: 'esnext',
        },
        optimizeDeps: {
            esbuildOptions: {
                target: 'esnext',
            },
        },
        server: {
            host: true,
            port: 5173,
            allowedHosts: [
                'localhost',
                'imapplepie20.tplinkdns.com',
                'approid.team',
                publicHost,
            ].filter(Boolean),
            hmr: hmrDisabled
                ? false
                : hmrProtocol || hmrClientPort
                ? {
                      host: publicHost,
                      protocol: hmrProtocol,
                      clientPort: hmrClientPort,
                  }
                : undefined,
            proxy: {
                '/api': {
                    target: 'http://localhost:8081',
                    changeOrigin: true,
                },
                '/actuator': {
                    target: 'http://localhost:8081',
                    changeOrigin: true,
                },
            },
        },
    }
})
