import { ArrowRight, Info } from 'lucide-react'

interface PageExplanationProps {
    eyebrow: string
    title: string
    body: string
    flow?: string
}

const PageExplanation = ({ eyebrow, title, body, flow }: PageExplanationProps) => {
    const flowSteps = flow?.split('->').map((step) => step.trim()).filter(Boolean) ?? []

    return (
        <section className="rounded-2xl border border-hud-border-secondary bg-hud-bg-secondary/70 p-5">
            <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
                <div className="max-w-3xl">
                    <div className="inline-flex items-center gap-2 rounded-full border border-hud-border-primary bg-hud-accent-primary/10 px-3 py-1 text-[11px] font-semibold text-hud-accent-primary">
                        <Info size={13} />
                        {eyebrow}
                    </div>
                    <h2 className="mt-3 text-xl font-semibold text-hud-text-primary">{title}</h2>
                    <p className="mt-2 text-sm leading-6 text-hud-text-secondary">{body}</p>
                </div>

                {flowSteps.length > 0 && (
                    <ol className="flex min-w-0 flex-wrap items-center gap-2 text-xs text-hud-text-muted">
                        {flowSteps.map((step, index) => (
                            <li key={`${step}-${index}`} className="inline-flex items-center gap-2">
                                <span className="rounded-full border border-hud-border-secondary bg-hud-bg-primary px-2.5 py-1 text-hud-text-secondary">
                                    {step}
                                </span>
                                {index < flowSteps.length - 1 && <ArrowRight size={13} />}
                            </li>
                        ))}
                    </ol>
                )}
            </div>
        </section>
    )
}

export default PageExplanation
