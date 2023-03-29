FROM adoptopenjdk/openjdk8:jre8u275-b01-alpine


LABEL	maintainer="ysenih@erpya.com; EdwinBetanc0urt@outlook.com" \
	description="ADempiere gRPC All In One Server used as ADempiere backend"


# Add operative system dependencies
#RUN apk add --no-cache libc6-compat fontconfig ttf-dejavu
RUN	rm -rf /var/cache/apk/* && \
    rm /usr/glibc-compat/lib/ld-linux-x86-64.so.2 && /usr/glibc-compat/sbin/ldconfig && \
	apk update && \
	apk add \
		bash \
	 	fontconfig \
		ttf-dejavu


# Init ENV with default values
ENV \
	SERVER_PORT="50059" \
	SERVICES_ENABLED="business; business_partner; core; dashboarding; dictionary; enrollment; file_management; general_ledger; in_out; invoice; issue_management; log; material_management; order; payment; payment_print_export; payroll_action_notice; pos; product; security; store; time_control; time_record; ui; user_customization; workflow;" \
	SERVER_LOG_LEVEL="WARNING" \
	SECRET_KEY="98D8032045502303C1F97FE5A5D40750A6D16D97C20A7BD9C757D2E957F2CA6E" \
	DB_HOST="localhost" \
	DB_PORT="5432" \
	DB_NAME="adempiere" \
	DB_USER="adempiere" \
	DB_PASSWORD="adempiere" \
	DB_TYPE="PostgreSQL" \
	ADEMPIERE_APPS_TYPE="wildfly" \
	TZ="America/Caracas"


EXPOSE ${SERVER_PORT}

WORKDIR /opt/Apps/backend/bin/

# Add connection template and start script files
ADD build/tmp /opt/Apps/backend/
ADD "build-docker/all_in_one_connection.yaml" "build-docker/start.sh" "/opt/Apps/backend/bin/"

RUN apk add --no-cache bash fontconfig ttf-dejavu && \
	addgroup adempiere && \
	adduser --disabled-password --gecos "" --ingroup adempiere --no-create-home adempiere && \
	chown -R adempiere /opt/Apps/backend/ && \
	chmod +x start.sh

USER adempiere

# Start app
ENTRYPOINT ["sh" , "start.sh"]
