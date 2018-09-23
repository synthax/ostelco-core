#!/bin/bash
#set -x

exportId=$1
if [ -z "$1" ]; then
    echo "Specify the id of the export operation you want to delete"
    exit
fi
exportId=${exportId//-}
exportId=${exportId,,}
projectId=pantel-2decb

msisdnPseudonymsTable=exported_pseudonyms.${exportId}_msisdn
subscriberPseudonymsTable=exported_pseudonyms.${exportId}_subscriber
dataConsumptionTable=exported_data_consumption.$exportId
csvfile=$projectId-dataconsumption-export/$exportId.csv

echo "Cleaning all data for export $exportId"
echo "Deleting Table $msisdnPseudonymsTable"
bq rm -f -t $msisdnPseudonymsTable
echo "Deleting Table $subscriberPseudonymsTable"
bq rm -f -t $subscriberPseudonymsTable
echo "Deleting Table $dataConsumptionTable"
bq rm -f -t $dataConsumptionTable
echo "Deleting csv gs://$csvfile"
gsutil rm gs://$csvfile

echo "Finished cleanup for the export $exportId"