(async function (ask, version, lang) {
    // As the site's own Lines tab asks (its moovitLineApiHeaders): the same anonymous session as a search, then each
    // operator's lines (/api/lines/agency, a hundred at a time), or one line's day (/api/lines/group-trips), which comes
    // back here only as much as Kav reads of it: each way's stops, road, and trips with their times.
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
        if (!response.ok && path === '/api/share/itinerary/get' && response.status === 404) return null;
        if (!response.ok) throw new Error('Moovit web HTTP ' + response.status + ' ' + path.split('?')[0]);
        const text = await response.text();
        if (!text && path === '/api/share/itinerary/get') return null;
        try { return JSON.parse(text); }
        catch { throw new Error('Moovit returned invalid JSON for ' + path.split('?')[0]); }
    }
    // Each line's ways in a day: a way per pattern its trips take, with each trip's start and its seconds to each stop.
    function waysOf(day) {
        const ways = [];
        for (const line of (day.lineTrips || {}).linesTrips || []) {
            const intervals = {};
            for (const t of line.tripIntervals || []) intervals[t.tripIntervalsId] = t;
            const byPattern = {};
            for (const g of line.tripGroups || []) {
                const t = intervals[g.tripIntervalsId];
                if (!t) continue;
                const way = byPattern[t.tripPatternId] ||
                    (byPattern[t.tripPatternId] = {line: line.lineId, pattern: t.tripPatternId, shape: g.tripShapeId, profiles: [], trips: []});
                const profile = way.profiles.length;
                way.profiles.push(t.intervals || []);
                const blocked = {};
                for (const stop of g.stopEmbarkations || []) if ((stop.stopEmbarkation || {}).pickup === 2) {
                    (blocked[stop.tripId] || (blocked[stop.tripId] = [])).push(stop.stopIndex);
                }
                (g.departuresUtc || []).forEach((at, i) =>
                    way.trips.push([Number(at), profile, blocked[(g.departureTripIds || [])[i]] || []]));
            }
            ways.push(...Object.values(byPattern));
        }
        return ways;
    }
    const user = (await call('/api/user/all', {metroId, metroSlug: 'israel-1', customerId, langId})).userCombination || {};
    if (user.userKey) headers.moovit_user_key = user.userKey;
    if (user.gtfsLanguage) headers.moovit_gtfs_language = user.gtfsLanguage;
    const metro = (await call('/api/metro/' + metroId + '/combined?langId=' + langId)).metroAreaData || {};
    const agencies = (metro.agencies || []).map(a => [a.agencyId, a.agencyName, a.routeType]);
    // A trip shared from Moovit, by its id, as the site's page opens such a link: its itinerary, and its two ends where
    // the site finds them, the first and last legs that say (a walk's journey, else a ride's first or last stop), and
    // when it leaves.
    if (ask.kind === 'shared') {
        const got = await call('/api/share/itinerary/get', {id: ask.id});
        if (!got || !got.itinerary || got.code != null) return {expired: true};
        const legs = got.itinerary.legs || [], stops = (got.supplementalData || {}).mVStopSyncedMetaDataList || [];
        function end(first) {
            for (let n = first ? 0 : legs.length - 1; n >= 0 && n < legs.length; n += first ? 1 : -1) {
                const leg = legs[n];
                for (const key of Object.keys(leg)) {
                    const journey = (leg[key] || {}).journey;
                    if (journey) return first ? journey.origin : journey.dest;
                }
                const choice = leg.lineWithAlternativesLeg || {};
                const ride = leg.lineLeg || (leg.multiLineLeg || {}).lineLeg ||
                    (choice.alternativeLines || [])[Number(choice.primaryAlternativeIndex || 0)];
                const ids = (ride || {}).stopSequenceIds || [];
                const stop = ids.length && stops.find(s => s.stopId === ids[first ? 0 : ids.length - 1]);
                if (stop) return {caption: stop.stopName, latlon: stop.stopLocation};
            }
            return null;
        }
        const place = p => p && p.latlon ? [p.latlon.latitude / 1e6, p.latlon.longitude / 1e6, p.caption || ''] : null;
        const first = legs[0] || {};
        return {from: place(end(true)), to: place(end(false)), at: Number(((first[Object.keys(first)[0]] || {}).time || {}).startTimeUtc || 0),
            itinerary: got.itinerary, supplementalData: got.supplementalData, agencies};
    }
    if (ask.kind === 'lines') {
        const groups = [];
        async function operator(id) {
            for (let offset = 0; ; offset += 100) {
                const page = await call('/api/lines/agency', {agencyId: id, offset, limit: 100});
                const items = page.lineGroupItems || [];
                for (const g of items) groups.push([g.lineGroupId, id, g.shortName || '', g.longName || '',
                    (g.subtitle || []).map(s => s.text || '').join('')]);
                if (items.length < 100 || offset + items.length >= (page.total || 0)) return;
            }
        }
        const queue = agencies.map(a => a[0]);
        await Promise.all([0, 1, 2, 3, 4, 5].map(async () => { while (queue.length) await operator(queue.shift()); }));
        return {agencies, groups};
    }
    function compact(day, used) {
        const group = day.syncedLineGroup || {};
        return {
            day: used,
            ways: waysOf(day),
            patterns: (day.syncedPatterns || []).map(p => [p.tripPatternId, p.stopIds || []]),
            stops: (day.syncedStops || []).map(s => [s.stopId, s.stopCode || '', s.stopName || '',
                (s.stopLocation || {}).latitude, (s.stopLocation || {}).longitude]),
            shapes: (day.syncedShapes || []).map(s => [s.shapeId, s.encodedShape || '']),
            lines: (group.lineSummaries || []).map(l => [l.lineId, l.origin || '', l.destination || '', l.routeLongName || '']),
            agencies
        };
    }
    if (ask.kind === 'board') {
        const days = [], queue = ask.groups.slice();
        await Promise.all([0, 1, 2, 3, 4, 5].map(async () => {
            while (queue.length) {
                const group = queue.shift();
                const day = await call('/api/lines/group-trips',
                    {lineGroupId: group, dayOffset: ask.day, isExtended: 1, revision: metro.revisionNumber});
                days.push({group, ...compact(day, ask.day)});
            }
        }));
        return {days};
    }
    // The day asked, or with ahead, the first from it that has trips.
    let day = {}, used = ask.day, ways = [];
    for (let k = 0; k < (ask.ahead || 1) && !ways.length; k++) {
        used = ask.day + k;
        day = await call('/api/lines/group-trips',
            {lineGroupId: ask.group, dayOffset: used, isExtended: 1, revision: metro.revisionNumber});
        ways = waysOf(day);
    }
    return compact(day, used);
})
