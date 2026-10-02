export function formatRating(rating) {
    if (!rating) return '등급 미확인';
    return /^\d+$/.test(rating) ? `${rating}세` : rating;
}