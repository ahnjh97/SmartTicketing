"""Optional JDBC integration check; owns and removes one temporary schema."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import uuid

from live import read_catalog
from runner import HERE, REPO, environment, java_executable

CHECK = r'''
import java.sql.*;
class SnapshotCheck {
 public static void main(String[] args) throws Exception {
  Class.forName("com.mysql.cj.jdbc.Driver");
  String name=args[0];
  if(!name.matches("nearby_bench_[a-f0-9]{32}")) throw new IllegalArgumentException();
  try(var c=DriverManager.getConnection(System.getenv("BENCH_MYSQL_URL"),System.getenv("BENCH_MYSQL_USER"),System.getenv("BENCH_MYSQL_PASSWORD")); var s=c.createStatement()) {
   s.executeUpdate("CREATE DATABASE "+name+" CHARACTER SET utf8mb4");
   try {
    s.executeUpdate("CREATE TABLE "+name+".theaters (kakao_place_id VARCHAR(255), name VARCHAR(100), brand VARCHAR(30), address VARCHAR(255), latitude DECIMAL(10,7), longitude DECIMAL(10,7), is_active BOOLEAN)");
    try(var p=c.prepareStatement("INSERT INTO "+name+".theaters VALUES (?, ?, 'CGV', ?, ?, 126.978, ?)")) {
     for(int i=0;i<4;i++) {
      p.setString(1,"test-"+i); p.setString(2,"영화관, \"테스트\""); p.setString(3,i==1?"경기도 수원":"서울특별시 중구");
      if(i==3) p.setNull(4,Types.DECIMAL); else p.setDouble(4,37.5665);
      p.setBoolean(5,i!=2); p.executeUpdate();
     }
    }
    Snapshot.main(new String[]{args[1]});
    try(var r=s.executeQuery("SELECT COUNT(*) FROM "+name+".theaters")) { r.next(); if(r.getInt(1)!=4) throw new AssertionError("Source changed"); }
   } finally { s.executeUpdate("DROP DATABASE "+name); }
  }
 }
}
'''


@unittest.skipUnless(os.environ.get('BENCH_TEST_MYSQL') == '1', 'Set BENCH_TEST_MYSQL=1 for local MySQL integration')
class SnapshotMysqlTests(unittest.TestCase):
    def test_read_only_seoul_snapshot(self):
        env = environment(REPO / '.env.benchmark')
        java = java_executable(env)
        drivers = list((Path.home() / '.gradle/caches/modules-2/files-2.1/com.mysql/mysql-connector-j').rglob('mysql-connector-j-*.jar'))
        self.assertTrue(drivers, 'Run bootJar once to cache the JDBC driver')
        schema = 'nearby_bench_' + uuid.uuid4().hex
        env.update(BENCH_SOURCE_DB_URL='jdbc:mysql://127.0.0.1:3306/' + schema,
                   BENCH_SOURCE_DB_USERNAME=env['BENCH_MYSQL_USER'],
                   BENCH_SOURCE_DB_PASSWORD=env['BENCH_MYSQL_PASSWORD'])
        with tempfile.TemporaryDirectory(prefix='nearby-snapshot-check-') as tmp:
            tmp = Path(tmp)
            (tmp / 'SnapshotCheck.java').write_text(CHECK, encoding='utf-8')
            subprocess.run([str(Path(java).with_name('javac.exe' if os.name == 'nt' else 'javac')),
                            '-encoding', 'UTF-8', '-d', str(tmp), str(HERE / 'Snapshot.java'),
                            str(tmp / 'SnapshotCheck.java')], check=True, env=env)
            subprocess.run([java, '-cp', str(tmp) + os.pathsep + str(drivers[0]), 'SnapshotCheck',
                            schema, str(tmp / 'catalog.csv')], check=True, env=env)
            rows = read_catalog(tmp / 'catalog.csv')
            self.assertEqual(len(rows), 1)
            self.assertEqual(rows[0]['id'], 'test-0')
            self.assertEqual(rows[0]['name'], '영화관, "테스트"')
