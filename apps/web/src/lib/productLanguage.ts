export const PRODUCT_TERMS = {
    pmsFull: '내 음악 보관함(PMS)',
    pmsShort: '내 음악(PMS)',
    emsFull: '음악 탐색 풀(EMS)',
    emsShort: '음악 탐색(EMS)',
    gmsFull: '추천 게이트(GMS)',
    gmsShort: '추천(GMS)',
} as const

export const ACTION_LABELS = {
    import: '가져오기',
    connect: '연결하기',
    preview: '미리보기',
    save: '저장하기',
    like: '좋아요',
    pass: '넘기기',
    play: '재생',
    playAll: '전체 재생',
    open: '열기',
    retry: '다시 시도',
    refresh: '새로고침',
    approve: '승인',
    reject: '거절',
    resolve: '해결',
} as const

export const OPERATOR_DIAGNOSTICS = {
    title: '운영자 전용 진단',
    description: '추천 모델과 게이트 상태를 점검하기 위한 정보입니다. 일반 사용자가 선택해야 하는 항목은 아닙니다.',
} as const

export const PAGE_EXPLANATIONS = {
    pms: {
        eyebrow: PRODUCT_TERMS.pmsFull,
        title: '플랫폼을 바꿔도 남는 내 음악 기준점',
        body: '연결한 스트리밍 플랫폼에서 가져온 플레이리스트와 사이트에서 저장한 추천곡이 모이는 개인 음악 보관함입니다.',
        flow: '플랫폼 연결 -> 플레이리스트 가져오기 -> 오디오 특성 보강 -> 취향 모델 학습',
    },
    ems: {
        eyebrow: PRODUCT_TERMS.emsFull,
        title: '외부 음악 후보를 모으는 탐색 공간',
        body: '외부 공개 플레이리스트와 트렌드에서 새로운 추천 후보를 찾는 탐색 공간입니다.',
        flow: '검색/수집 -> 후보 확인 -> 재생/상세 확인 -> GMS 추천 후보로 활용',
    },
    gmsPreview: {
        eyebrow: PRODUCT_TERMS.gmsFull,
        title: '추천 후보를 듣고 저장하기 전에 검토합니다',
        body: 'PMS 보관함과 EMS 후보를 비교해 지금 저장하거나 들어볼 만한 추천 곡을 검토합니다.',
        flow: '기준 선택 -> 추천 미리보기 생성 -> 후보 듣기/좋아요/넘기기/저장 -> PMS 취향 신호에 반영',
    },
    gmsPlaylists: {
        eyebrow: PRODUCT_TERMS.gmsFull,
        title: '취향 모델이 통과시킨 플레이리스트',
        body: 'EMS에서 수집한 플레이리스트 중 내 취향 모델을 통과한 묶음을 확인하고 PMS에 저장합니다.',
        flow: 'EMS 후보 수집 -> 취향 모델 평가 -> 추천 플레이리스트 확인 -> PMS 저장',
    },
} as const

export const RECOMMENDATION_SIGNAL_LABELS: Record<string, { label: string; description: string }> = {
    affinity: {
        label: '취향 일치도',
        description: '사용자 취향 신호와 후보가 얼마나 가까운지 봅니다.',
    },
    novelty: {
        label: '새로움',
        description: '최근 청취 패턴과 적당히 다른 발견인지 봅니다.',
    },
    coherence: {
        label: '흐름 안정성',
        description: '플레이리스트 안에서 분위기와 출처 흐름이 자연스러운지 봅니다.',
    },
    diversity: {
        label: '다양성',
        description: '아티스트, 장르, 플랫폼 분포가 한쪽으로 치우치지 않는지 봅니다.',
    },
    redundancy: {
        label: '반복 위험',
        description: '이미 비슷한 아티스트나 곡이 너무 많지 않은지 봅니다.',
    },
    confidence: {
        label: '근거 신뢰도',
        description: 'trackId, 오디오 특성, 출처 플레이리스트 단서가 충분한지 봅니다.',
    },
}

export const recommendationSignalLabel = (axis: string) =>
    RECOMMENDATION_SIGNAL_LABELS[axis]?.label ?? axis

export const recommendationSignalDescription = (axis: string) =>
    RECOMMENDATION_SIGNAL_LABELS[axis]?.description ?? '추천 계산에 사용된 내부 신호입니다.'
