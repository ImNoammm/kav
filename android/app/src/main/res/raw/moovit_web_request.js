(async function (query, version, lang) {
    // As the site's own page asks (its apiFetch): its anonymous session for Israel's metro (1, "israel-1") and the
    // web customer, the metro's operators, then the search and its result pages.
    const metroId = 1, customerId = '4908', langId = lang === 'he' ? 62 : 41;
    const token = await window.AwsWafIntegration.getToken();
    const headers = {
        Accept: 'application/json',
        moovit_app_type: 'WEB_TRIP_PLANNER',
        moovit_phone_type: '2',
        moovit_client_version: version,
        moovit_metro_id: String(metroId),
        moovit_customer_id: customerId,
        'x-aws-waf-token': token
    };
    async function call(path, init) {
        const response = await fetch(path, Object.assign({credentials: 'same-origin'}, init,
            {headers: Object.assign({}, headers, init && init.headers)}));
        if (!response.ok) throw new Error('Moovit web HTTP ' + response.status + ' ' + path.split('?')[0]);
        return response.json();
    }
    const user = (await call('/api/user/all', {
        method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({metroId, metroSlug: 'israel-1', customerId, langId})
    })).userCombination || {};
    if (user.userKey) headers.moovit_user_key = user.userKey;
    if (user.gtfsLanguage) headers.moovit_gtfs_language = user.gtfsLanguage;
    const metro = (await call('/api/metro/' + metroId + '/combined?langId=' + langId)).metroAreaData || {};
    const from = {lat: query.fromLocation_latitude / 1e6, lng: query.fromLocation_longitude / 1e6};
    const at = {headers: {moovit_user_latitude: String(from.lat), moovit_user_longitude: String(from.lng)}};
    const search = await call('/api/route/suggested-routes-start?' + new URLSearchParams(query), at);
    if (!search.token) throw new Error('Moovit web did not return a route search');
    const pages = [];
    let offset = 0;
    for (let polls = 1; polls <= 46; polls++) {
        const page = await call('/api/route/suggested-routes-poll?' + new URLSearchParams({
            token: search.token, offset, fromLocation_latitude: from.lat, fromLocation_longitude: from.lng
        }), at);
        pages.push(page);
        offset += (page.results || []).length;
        if (page.completed || (page.errors || []).length) return {pages, agencies: metro.agencies || []};
        await new Promise(resolve => setTimeout(resolve, polls <= 20 ? 200 : 1000));
    }
    if (offset > 0) return {pages, agencies: metro.agencies || []};
    throw new Error('Moovit web route search timed out');
})
