import json
import unittest

from harness import catalog_fixtures, distance, selected_theaters, validate_response
from runner import planned_calls


class CurrentFlowTests(unittest.TestCase):
    def setUp(self):
        self.scenario = dict(name='current', latitude=37.5665, longitude=126.978,
            theaters=[dict(id=str(i), name=str(i), brand='CGV', latitude=37.5665 + offset,
                           longitude=126.978) for i, offset in enumerate([.005, .01, .04])])
        catalog_fixtures([self.scenario])

    def rows(self, sort):
        scenario = dict(self.scenario, sort=sort)
        rows = [dict(kakaoPlaceId=t['id'], distance=distance(scenario, t),
                     transitMinutes=t['transitMinutes'] if sort == 'TRANSIT' else None,
                     transitDistance=t['transitDistance'] if sort == 'TRANSIT' else None,
                     walkMinutes=t['transitMinutes'] if sort == 'WALK' else None,
                     walkDistance=t['transitDistance'] if sort == 'WALK' else None)
                for t in selected_theaters(scenario)]
        return scenario, rows

    def test_real_sort_contracts_and_call_plan(self):
        scenarios = []
        for sort, count in [('DISTANCE', 3), ('TRANSIT', 3), ('WALK', 2)]:
            scenario, rows = self.rows(sort)
            self.assertEqual(len(rows), count)
            self.assertTrue(validate_response(200, json.dumps(rows), scenario, 'stub')[0])
            scenarios.append(scenario)
        self.assertEqual(planned_calls(scenarios, ['HEAD'], 1, 0, 1), dict(search=0, transit=3, walk=2))
        self.assertEqual(planned_calls(scenarios, ['HEAD'], 2, 1, 2, live=False), dict(search=0, transit=24, walk=16))

    def test_distance_rejects_unexpected_routing(self):
        scenario, rows = self.rows('DISTANCE')
        rows[0]['transitMinutes'] = 1
        self.assertEqual(validate_response(200, json.dumps(rows), scenario, 'stub')[1], 'unexpected_transit')

    def test_walk_rejects_outside_2km_or_missing_route(self):
        scenario, rows = self.rows('WALK')
        rows[0]['walkMinutes'] = None
        self.assertEqual(validate_response(200, json.dumps(rows), scenario, 'stub')[1], 'missing_or_invalid_walk')
        scenario, rows = self.rows('WALK')
        rows.append(dict(kakaoPlaceId='2'))
        self.assertEqual(validate_response(200, json.dumps(rows), scenario, 'stub')[1], 'candidate_mismatch')

    def test_transit_must_include_all_candidates_not_top_n(self):
        scenario, rows = self.rows('TRANSIT')
        self.assertEqual(validate_response(200, json.dumps(rows[:1]), scenario, 'stub')[1], 'candidate_mismatch')

    def test_empty_walk_is_valid_but_unexpected_empty_transit_is_not(self):
        scenario = dict(self.scenario, sort='WALK', theaters=self.scenario['theaters'][2:])
        self.assertTrue(validate_response(200, '[]', scenario, 'stub')[0])
        scenario['sort'] = 'TRANSIT'
        self.assertFalse(validate_response(200, '[]', scenario, 'stub')[0])
