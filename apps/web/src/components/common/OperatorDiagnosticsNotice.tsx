import { ShieldCheck } from 'lucide-react'
import { OPERATOR_DIAGNOSTICS } from '@/lib/productLanguage'

interface OperatorDiagnosticsNoticeProps {
    compact?: boolean
}

const OperatorDiagnosticsNotice = ({ compact = false }: OperatorDiagnosticsNoticeProps) => (
    <div className={`rounded-xl border border-amber-300/30 bg-amber-300/10 ${compact ? 'p-3' : 'p-4'}`}>
        <div className="flex items-start gap-3">
            <span className="mt-0.5 rounded-lg bg-amber-300/15 p-2 text-amber-200">
                <ShieldCheck size={compact ? 14 : 16} />
            </span>
            <div>
                <p className="text-xs font-semibold text-amber-100">{OPERATOR_DIAGNOSTICS.title}</p>
                <p className="mt-1 text-xs leading-5 text-hud-text-muted">{OPERATOR_DIAGNOSTICS.description}</p>
            </div>
        </div>
    </div>
)

export default OperatorDiagnosticsNotice
