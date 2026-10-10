const SDK_URL = 'https://js.tosspayments.com/v2/standard';

function loadSdk() {
    if (window.TossPayments) return Promise.resolve();
    return new Promise((resolve, reject) => {
        const existing = document.querySelector('script[data-toss-payments-sdk]');
        const script = existing || document.createElement('script');
        const onLoad = () => window.TossPayments ? resolve() : reject(new Error('토스 결제 SDK를 불러오지 못했습니다.'));
        const onError = () => reject(new Error('토스 결제 SDK를 불러오지 못했습니다. 네트워크를 확인해주세요.'));
        script.addEventListener('load', onLoad, { once: true });
        script.addEventListener('error', onError, { once: true });
        if (!existing) {
            script.src = SDK_URL;
            script.async = true;
            script.dataset.tossPaymentsSdk = 'true';
            document.head.appendChild(script);
        }
    });
}

function customerKeyFor(userId) {
    const slot = `toss.customer-key.${userId}`;
    let key = sessionStorage.getItem(slot);
    if (!key) {
        key = crypto.randomUUID();
        sessionStorage.setItem(slot, key);
    }
    return key;
}

export async function openTossPayment(order, reservationId, userId) {
    const clientKey = import.meta.env.VITE_TOSS_CLIENT_KEY?.trim();
    if (!clientKey) throw new Error('프론트엔드 환경변수 VITE_TOSS_CLIENT_KEY가 없습니다. Vite를 재시작해주세요.');
    await loadSdk();
    const tossPayments = window.TossPayments(clientKey);
    const widgets = tossPayments.widgets({ customerKey: customerKeyFor(userId) });
    await widgets.setAmount({ currency: 'KRW', value: order.amount });
    const paymentWindow = await widgets.renderPaymentWindow();
    const returnUrl = new URL(window.location.pathname, window.location.origin);
    returnUrl.searchParams.set('tossReservationId', String(reservationId));
    const successUrl = returnUrl.toString();
    const failUrl = new URL(successUrl);
    failUrl.searchParams.set('tossFailed', '1');
    paymentWindow.on('paymentRequest', () => widgets.requestPayment({
        orderId: order.orderId,
        orderName: order.orderName,
        successUrl,
        failUrl: failUrl.toString(),
    }));
}
