"""Synthetic data and local-only tokens for the disposable admission benchmark DB."""
import base64
import datetime
import hashlib
import hmac
import json
import time


def fixture(users, date):
    sql = ['START TRANSACTION;']
    for i in range(1, 201):
        sql.append(f"INSERT INTO movies(id,tmdb_movie_id,title,running_time,rating,release_date,is_active,audience_count) VALUES({i},{99000000+i},'Load movie {i}',120,'ALL','{date}',1,{100000-i});")
    for i in range(1, 21):
        sql.append(f"INSERT INTO theaters(id,brand,name,address,kakao_place_id,latitude,longitude,is_active) VALUES({i},'CGV','Load theater {i}','Seoul','load-{i}',37.5665,126.978,1);")
        sql.append(f"INSERT INTO screens(id,theater_id,name,is_active) VALUES({i},{i},'Load screen',1);")
        sql.append(f"INSERT INTO showtimes(id,movie_id,screen_id,start_time,end_time,total_seats,available_seats,price_per_person,status,created_at,updated_at) VALUES({i},1,{i},'{date} 18:00:00','{date} 20:00:00',100,100,10000,'SCHEDULED',NOW(),NOW());")
        for n in range(100):
            sid = (i-1)*100+n+1
            sql.append(f"INSERT INTO seats(id,screen_id,seat_row,seat_number,seat_position,adjacency_segment,position_in_segment,is_active) VALUES({sid},{i},'{chr(65+n//10)}',{n%10+1},'MIDDLE_MIDDLE','row-{n//10}',{n%10+1},1);")
            sql.append(f"INSERT INTO showtime_seats(showtime_id,seat_id,status) VALUES({i},{sid},'AVAILABLE');")
    tokens = {}
    def encoded(value):
        return base64.urlsafe_b64encode(json.dumps(value, separators=(',', ':')).encode()).decode().rstrip('=')
    for i in range(1, users + 1):
        sql.append(f"INSERT INTO users(id,name,login_id,nickname,birth_date,status) VALUES({i},'Load user {i}','load-{i}','load-{i}','1990-01-01','ACTIVE');")
        sql.append(f"INSERT INTO user_preferred_seats(user_id,seat_position,priority) VALUES({i},'MIDDLE_MIDDLE',1);")
        sql.append(f"INSERT INTO user_preferred_theaters(user_id,theater_id,priority) VALUES({i},{(i-1)%20+1},1);")
        now = int(time.time())
        value = encoded({'alg': 'HS256'}) + '.' + encoded({'iss': 'SmartTicketing', 'sub': str(i), 'iat': now, 'exp': now+3600, 'type': 'access'})
        signature = hmac.new(b'local-admission-test-secret-012345678901234567890123456789', value.encode(), hashlib.sha256).digest()
        tokens[i] = value + '.' + base64.urlsafe_b64encode(signature).decode().rstrip('=')
    sql.append('COMMIT;')
    return '\n'.join(sql), {'date': date, 'tokens': tokens}
