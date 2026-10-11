// Keep viewing conditions, but never carry an executable booking route into Back history.
export function bookingSelectionPath(pathname, params) {
    const selection = new URLSearchParams(params);
    for (const key of ['entry', 'smart', 'plan', 'candidate', 'candidates', 'group', 'reservation', 'seats', 'tossResult']) {
        selection.delete(key);
    }
    const query = selection.toString();
    return `${pathname}${query ? `?${query}` : ''}`;
}
