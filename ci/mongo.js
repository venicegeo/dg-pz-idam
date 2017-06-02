conn = new Mongo();
db = conn.getDB("Piazza")
db.UserProfiles.insertOne({"username" : "coastline", "credential" : "$2a$12$7EK1s7OevtebPqPSuCgls.Za6BWzUxYDS..HZBAcuHP74/2SDv5.i", "distinguishedName" : "coastline.admin", "createdBy" : "system", "adminCode" : "", "dutyCode" : "", "country" : "us", "npe" : false, "lastUpdatedOn" : "2017-05-31T10:35:50.306-04:00", "createdOn" : "2017-05-31T10:35:50.306-04:00" })
