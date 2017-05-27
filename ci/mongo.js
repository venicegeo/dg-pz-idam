conn = new Mongo();
db = conn.getDB("Piazza")
db.UserProfiles.insertOne({ "username" : "admin", "credential" : "$2a$12$YYOG/JBAY7keBYzk6tmUq.t1SkBgMaZ1I3Hr473l862cXuIm4DHz2", "distinguishedName" : "primary.admin", "createdBy" : "system", "adminCode" : "", "dutyCode" : "", "country" : "us", "npe" : false, "createdOn" : "2017-05-26T11:22:23.050-04:00", "lastUpdatedOn" : "2017-05-26T11:22:23.050-04:00" })
