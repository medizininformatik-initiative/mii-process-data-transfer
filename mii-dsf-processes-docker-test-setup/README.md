# Testing using Docker Setup

Build the project from the root directory of this repository by executing the following command.

```sh
mvn clean package
```

Add entries to your hosts file
```
127.0.0.1	dic
127.0.0.1	cos
```

*A total of four console windows are required. Start docker-compose commands from sub-folder:* `mii-dsf-processes/mii-dsf-processes-docker-test-setup`

Console 1: Start DIC HAPI FHIR Server 
```sh
docker-compose up dic-fhir-store-hapi
```
Access at http://localhost:8080/fhir/

Console 4: Start COS HAPI FHIR Server
```sh
docker-compose up cos-fhir-store-hapi
```
Access at http://localhost:8081/fhir/

Console 2: Start DIC DSF FHIR Server and wait till started
```sh
docker-compose up -d dic-fhir && docker-compose logs -f dic-fhir
```
Console 2: Disconnect from log output (Ctrl-C) if Server started
Console 2: Start DIC DSF BPE Server
```sh
docker-compose up -d dic-bpe && docker-compose logs -f dic-fhir dic-bpe
````

Console 3: Start COS DSF FHIR Server and wait till started
```sh
docker-compose up -d cos-fhir && docker-compose logs -f cos-fhir
```
Console 3: Disconnect from log output (Ctrl-C) if Server started
Console 3: Start COS DSF BPE Server
```sh
docker-compose up -d cos-bpe && docker-compose logs -f cos-fhir cos-bpe
````

<!--
Webbrowser at http://localhost:8080/fhir/: Add Demo Data to DIC HAPI FHIR Server via Transaction-Bundle at
[DicFhirStore_Demo.xml](../mii-dsf-process-projectathon-data-transfer/src/test/resources/fhir/Bundle/DicFhirStore_Demo.xml) 
-->

*Start curl commands from root-folder:* `mii-dsf-processes`

Console 6: Execute Demo Transaction-Bundle for HAPI
```sh
curl -H "Accept: application/xml+fhir" -H "Content-Type: application/fhir+xml" \
-d @mii-dsf-process-projectathon-data-transfer/src/test/resources/fhir/Bundle/DicFhirStore_Demo.xml \
http://localhost:8080/fhir
```

Console 6: Start Data Send Process at DIC using the following command
```sh
curl -H "Accept: application/xml+fhir" -H "Content-Type: application/fhir+xml" \
-d @mii-dsf-process-projectathon-data-transfer/src/test/resources/fhir/Task/TaskStartDataSend_Demo.xml \
--ssl-no-revoke --cacert mii-dsf-processes-test-data-generator/cert/ca/testca_certificate.pem \
--cert mii-dsf-processes-test-data-generator/cert/Webbrowser_Test_User/Webbrowser_Test_User_certificate.pem \
--key mii-dsf-processes-test-data-generator/cert/Webbrowser_Test_User/Webbrowser_Test_User_private-key.pem \
https://dic/fhir/Task
```

Console X: Check data transferred to COS
```sh
curl http://localhost:8081/fhir/DocumentReference
```

Console X: Stop everything
```sh
docker-compose down -v
```