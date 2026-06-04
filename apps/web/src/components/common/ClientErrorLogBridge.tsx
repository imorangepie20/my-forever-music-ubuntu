import { useEffect } from 'react'
import { useAuthSession } from '@/contexts/AuthSessionContext'
import { recordClientApplicationError } from '@/services/api'

const toMessage = (value: unknown) => {
    if (value instanceof Error) {
        return value.message
    }
    if (typeof value === 'string') {
        return value
    }
    try {
        return JSON.stringify(value)
    } catch {
        return 'Unknown client error'
    }
}

const toStackTrace = (value: unknown) =>
    value instanceof Error ? value.stack ?? null : null

const ClientErrorLogBridge = () => {
    const { session } = useAuthSession()

    useEffect(() => {
        const handleError = (event: ErrorEvent) => {
            void recordClientApplicationError({
                source: 'web-runtime',
                severity: 'error',
                error_type: event.error instanceof Error ? event.error.name : 'window_error',
                message: event.message || toMessage(event.error),
                stack_trace: toStackTrace(event.error),
                request_method: 'BROWSER',
                request_path: window.location.pathname,
                user_id: session?.userId ?? null,
                context_json: JSON.stringify({
                    filename: event.filename,
                    lineno: event.lineno,
                    colno: event.colno,
                }),
            })
        }

        const handleUnhandledRejection = (event: PromiseRejectionEvent) => {
            void recordClientApplicationError({
                source: 'web-runtime',
                severity: 'error',
                error_type: event.reason instanceof Error ? event.reason.name : 'unhandled_rejection',
                message: toMessage(event.reason),
                stack_trace: toStackTrace(event.reason),
                request_method: 'PROMISE',
                request_path: window.location.pathname,
                user_id: session?.userId ?? null,
            })
        }

        window.addEventListener('error', handleError)
        window.addEventListener('unhandledrejection', handleUnhandledRejection)
        return () => {
            window.removeEventListener('error', handleError)
            window.removeEventListener('unhandledrejection', handleUnhandledRejection)
        }
    }, [session?.userId])

    return null
}

export default ClientErrorLogBridge
