// Match the server's existing ticket-age rule for the viewing year.
export function isYouthMember(user, date) {
    return Boolean(user?.birthDate && Number(date.slice(0, 4)) - Number(user.birthDate.slice(0, 4)) < 19);
}

export function audienceCounts(params, youthMember) {
    const adult = params.has('adult') ? Number(params.get('adult')) : params.has('party') ? Number(params.get('party')) : youthMember && params.has('youth') ? 0 : 1;
    const youth = params.has('youth') ? Number(params.get('youth')) : 0;
    if (youthMember) return { adultCount: 0, youthCount: Math.max(1, adult + youth) };
    return { adultCount: adult, youthCount: youth };
}
