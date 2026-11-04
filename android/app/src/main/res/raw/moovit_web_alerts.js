(async function (ask, version, lang) {
    // As the site's own alerts page asks: the same anonymous session as a search, then every alert of Israel's metro
    // (/api/alert/metro), or the full notices behind some of them (/api/alert/details). The metro's list comes back here
    // only as much as Kav reads of it: each line with an alert, by the ministry's own names for its ways.
    const metroId = 1, customerId = '4908', langId = lang === 'he' ? 62 : 41;
    const token = await window.AwsWafIntegration.getToken();
    const headers = {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        moovit_app_type: 'WEB_TRIP_PLANNER',
        moovit_phone_type: '2',
        moovit_client_version: version,
        moovit_metro_id: String(metroId),
        moovit_customer_id: customerId,
        'x-aws-waf-token': token
    };
    async function call(path, body) {
        const response = await fetch(path, {credentials: 'same-origin', method: body ? 'POST' : 'GET', headers,
            body: body ? JSON.stringify(body) : undefined});
        if (!response.ok) throw new Error('Moovit web HTTP ' + response.status + ' ' + path.split('?')[0]);
        return response.json();
    }
    const user = (await call('/api/user/all', {metroId, metroSlug: 'israel-1', customerId, langId})).userCombination || {};
    if (user.userKey) headers.moovit_user_key = user.userKey;
    if (user.gtfsLanguage) headers.moovit_gtfs_language = user.gtfsLanguage;
    if (ask.kind === 'details') {
        const ids = (ask.ids || []).slice(0, 40);
        if (!ids.length) return {alerts: []};
        const answer = await call('/api/alert/details?' + ids.map(id => 'ids=' + encodeURIComponent(id)).join('&'));
        return {alerts: (answer.serviceAlerts || []).map(a => ({
            id: String(a.alertId || ''), category: (a.serviceStatus || {}).category || 0, label: (a.serviceStatus || {}).desc || '',
            title: a.title || '', body: (a.desc || {}).data || '', html: (a.desc || {}).format === 1,
            from: a.activeFrom || 0, to: a.activeTo || 0, url: a.infoUrl || ''
        }))};
    }
    const metro = (await call('/api/metro/' + metroId + '/combined?langId=' + langId)).metroAreaData || {};
    const all = await call('/api/alert/metro', {id: metroId, lang: langId, revision: metro.revisionNumber});
    const groups = all.lineGroups || {};
    const names = {};
    for (const a of metro.agencies || []) names[a.agencyId] = a.agencyName || '';
    const lines = [];
    for (const agency of all.alertsForAgency || []) {
        for (const line of agency.lineAlerts || []) {
            const affected = line.affectedLine || {};
            const group = groups[String(affected.lineGroupId)] || {};
            lines.push([
                Number(affected.lineGroupId) || 0, names[agency.agencyId] || '', String(group.lineNumber || affected.name || ''),
                (line.serviceStatus || {}).category || 0, (line.serviceStatus || {}).desc || '',
                [...new Set((line.alertIds || []).map(String))],
                (group.lineSummaries || []).map(s => s.routeLongName || '').filter(Boolean)
            ]);
        }
    }
    return {lines};
})
