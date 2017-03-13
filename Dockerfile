FROM java:8-alpine
MAINTAINER Sage Han <zongshian@gmail.com>

ADD target/miaomfood-api-0.0.1-SNAPSHOT-standalone.jar /srv/miaomfood-api.jar

EXPOSE 8080

CMD ["java", "-cp", "/srv/miaomfood-api.jar", "clojure.main", "-m", "com.miaomfood.api.server"]
