export function formatShowDate(value) {
    return new Date(value).toLocaleDateString('sv-SE', { timeZone: 'Asia/Seoul' }).replaceAll('-', '.');
}

export function formatShowTime(value) {
    return new Date(value).toLocaleTimeString('ko-KR', {
        timeZone: 'Asia/Seoul', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
    });
}

export function formatShowtime(value) {
    return `${formatShowDate(value)} ${formatShowTime(value)}`;
}
