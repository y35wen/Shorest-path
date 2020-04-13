import java.io.*;  
import java.net.*;  
import java.nio.*;

public class router {

    static int topology_nbr[] = new int[5];  // store Topology database
    static link_cost[][] topology_linkcost= new link_cost[5][20];
    static int ribNext[][]=new int[5][5]; // store RIB information
    static int ribCost[][]=new int[5][5]; 
    static int router_id;

	public static void main(String[] args) throws Exception{

		//fields  
        String router_id_str = args[0]; // command line arguments
		router_id = Integer.parseInt(router_id_str);
        String nse_host = args[1];
	    InetAddress address= InetAddress.getByName(nse_host);
        String nse_port_str = args[2];
	    int nse_port = Integer.parseInt(nse_port_str);
        String router_port_str =args[3];
        int router_port = Integer.parseInt(router_port_str);

	    DatagramSocket socket = new DatagramSocket();
	    String routerid = "router"+router_id+".log";
	    PrintWriter routerWriter = new PrintWriter(routerid, "UTF-8");

	    // 0. SET UP
        for(int i=0;i<5;i++){
            for(int j=0;j<20;j++){
                topology_linkcost[i][j]= null;
            }
        }
        for(int i=0; i<5; i++){
            for(int j=0;j<5;j++){
                ribNext[i][j] = -1;
                ribCost[i][j] = Integer.MAX_VALUE;
            }
        }
        ribNext[router_id-1][router_id-1] = -2;
        ribCost[router_id-1][router_id-1] = 0;

	    // 1. When a router comes online, it sends an INIT packet(pkt_INIT) to the NSE
       	pkt_INIT initPkt = new pkt_INIT(router_id);
        byte[] initPkt_byte = initPkt.getUDPdata();
        int initPkt_length = 4;
    	DatagramPacket initPkt_packet = new DatagramPacket(initPkt_byte,initPkt_length,address,nse_port); 
    	socket.send(initPkt_packet);
        String printInit ="R"+router_id+" sends an INIT:router_id "+router_id; // write to router.log
    	routerWriter.println(printInit);

        // 2.The router receives a packet back from the NSE that contain the circuit database(circuit_DB)
        //	which essentially lists all the links/edges attached to this router
        byte[] recDB_byte = new byte[1024];
	    DatagramPacket recDB_packet = new DatagramPacket(recDB_byte,recDB_byte.length);  
	    socket.receive(recDB_packet);
        byte[] receDB = recDB_packet.getData();

        circuit_DB db = circuit_DB.parseUDPdata(receDB);  
        int numOfNeighbours = db.nbr_link;
        int[] listofN = new int[numOfNeighbours];   // list of neighbours of the current router
        for(int i=0;i<numOfNeighbours;i++){
            listofN[i] = db.linkcost[i].link;
        } 
        String printDB = "R"+router_id+" receives a CIRCUIT_DB:nbr_link "+numOfNeighbours;
        routerWriter.println(printDB);
	    // This information about links from circuit_DB is put into router's internal database called Link State
	    //	Database(LSDB/topology). A LSDB gives the overall picture of the network known to that specific router
        topology_nbr[router_id-1] = numOfNeighbours;
        for(int i=0;i<numOfNeighbours;i++){
            topology_linkcost[router_id-1][listofN[i]-1]=db.linkcost[i];
        }
        printTopoandRib(routerWriter); // print topology and RIB
        
        // 3. Each router then sends a HELLO_PDU to tell its neighbour. This means that a HELLO packet is sent
        //	on all the links that the router discovered in the previous step.
        for (int i=0; i<numOfNeighbours;i++) {
            int myneighbour = listofN[i];
            pkt_HELLO hellopkt = new pkt_HELLO(router_id,myneighbour);
            byte[] hellopkt_byte = hellopkt.getUDPdata();
            int hellopkt_length=8;
            DatagramPacket hellopkt_packet = new DatagramPacket(hellopkt_byte,hellopkt_length,address,nse_port); 
            socket.send(hellopkt_packet);
            String printHelloSend ="R"+router_id+" sends a HELLO:router_id "+router_id+" link_id "+myneighbour;
            routerWriter.println(printHelloSend);
        }

        int isRecvdHello[];
        isRecvdHello = new int[20];
        for(int i=0;i<20;i++){
            isRecvdHello[i]=-1;
        } 
        boolean helloOrLs=false;  // false=hello, true=ls

        while (true) {
			byte[] recData = new byte[1024];
	   		DatagramPacket receData_packet = new DatagramPacket(recData,recData.length);  
	    	socket.receive(receData_packet);
            int length = receData_packet.getLength();
            if(length==20) {
                helloOrLs=true;
            } else{
                helloOrLs=false;
            }
            byte[] receData = receData_packet.getData();
            
            //  4. Each router receiving a HELLO packet from its neighbour, sends a set of LS_PDU(pkt_LSPDU) to that neighbor,
            //  representing its current Topology Database state
            if(!helloOrLs){
                pkt_HELLO hellopkt = pkt_HELLO.parseUDPdata(receData);
                int helloSend = hellopkt.router_id;
                int helloTo = hellopkt.link_id;
                String printHelloRecvd="R"+router_id+" receives a HELLO:router_id "+helloSend+" link_id "+helloTo;
                routerWriter.println(printHelloRecvd);
                // respond to that neighbour by a set of LS PDUs containing its circuit databse.
                int via = helloTo;
                isRecvdHello[helloTo-1]=0;
                for (int i=0;i<20;i++) { 
                    if(topology_linkcost[router_id-1][i] !=null) {
                            link_cost lc = topology_linkcost[router_id-1][i];
                            pkt_LSPDU lspduPkt = new pkt_LSPDU(router_id,router_id,lc.link,lc.cost,via);
                            byte[] lspduPkt_byte = lspduPkt.getUDPdata();
                            int lspduPkt_length =20;                            
                            DatagramPacket lspduPkt_packet = new DatagramPacket(lspduPkt_byte,lspduPkt_length,address,nse_port);
                            socket.send(lspduPkt_packet);
                            String lsSsend="R"+router_id+" sends an LS PDU:sender "+router_id+",router_id "+router_id
                                            +",link_id "+lc.link+",cost "+lc.cost+",via "+via;
                            routerWriter.println(lsSsend);
                            routerWriter.flush();
                    }    
                }
            }

            // 5.Whenever a router receives an LS_PDU from one of its neighbours, it does the following step:
            else {
                pkt_LSPDU lspkt = pkt_LSPDU.parseUDPdata(receData);
                int lsSender = lspkt.sender;
                int lsId = lspkt.router_id;
                int lsLink =lspkt.link_id;
                int lsCost = lspkt.cost;
                int lsVia = lspkt.via;
                String lsReceived="R"+router_id+" receives an LS PDU:sender "+lsSender+",router_id "+lsId
                                  +",link_id "+lsLink+ ",cost "+lsCost+",via "+lsVia;
                routerWriter.println(lsReceived); 

              	if(topology_linkcost[lsId-1][lsLink-1] ==null) {  

                    // 1). add this LS_PDU information to the router's Link State Database/topology
                    link_cost newlc = new link_cost(lsLink,lsCost);
                    topology_nbr[lsId-1]++;
                    topology_linkcost[lsId-1][lsLink-1] =newlc;
               	    dijkstra();

                    // 2). inform each of the rest of neighbours by forwarding this LS_PDU to them
               	    for(int i=0; i<numOfNeighbours;i++) {
                        int neighbour =listofN[i];
                        if(isRecvdHello[neighbour-1]!=-1 && neighbour!=lsVia) {
                            // Note that a router sending LS_PDUs should set the 'sender' and 'via' fields of the LS_PDU 
                            //	packet to appropriate values
                            pkt_LSPDU sendLS = new pkt_LSPDU(router_id,lsId,lsLink,lsCost,neighbour);
                            byte[] sendLS_byte =sendLS.getUDPdata();
                            int sendLS_length =20;
                            DatagramPacket sendLS_packet = new DatagramPacket(sendLS_byte,sendLS_length,address,nse_port);
                            socket.send(sendLS_packet);
                            String forwardLs="R"+router_id +" sends an LS PDU:sender "+router_id+",router_id "
                                            +lsId+",link_id "+lsLink+",cost "+lsCost+",via "+neighbour;
                            routerWriter.println(forwardLs);
                        }
               	    }
                }
            }
            // done one round, Print the current Topology and RIB
            printTopoandRib(routerWriter); 
        }
    }
    

    //helper functions
    static void dijkstra() {

        // 1. Initialization
        int[] N = new int[5];
        int nodeU = router_id;
        N[nodeU-1] = 0; 
        for(int i=0;i<5;i++){
            N[i] = -1;
        }
        int dv=-1;
        // for all nodes v, if v adjacent to u then update ribCost(D(v))
        for(int v=0; v<5; v++) {
            if(nodeU==v+1) continue;
            if(dv!=-1) continue;
            for(int i=0;i<20;i++){
                if(dv!=-1) break;
                if(topology_linkcost[nodeU-1][i]!=null){
                    int linkU = topology_linkcost[nodeU-1][i].link;
                    for(int j=0;j<20;j++){
                        if(dv!=-1) break;
                        if(topology_linkcost[v][j]!=null && topology_linkcost[v][j].link==linkU){
                            dv=topology_linkcost[nodeU-1][i].cost;
                            break;
                        }
                    }
                }
            }
            if(dv >0){
                ribCost[router_id-1][v] = dv;
                ribNext[router_id-1][v] = v+1;
                dv = -1;
            }
        }
        // 2. Loop       
        for(int i =0; i<5;i++){
            // find w not in N such that D(w)/ribCost(w) is a minimum
            int w=-1;
            int currentmin =Integer.MAX_VALUE;
            for(int j=0;j<5;j++){
                if(N[j]==-1 && ribCost[router_id-1][j] < currentmin) {
                    currentmin = ribCost[router_id-1][j];
                    w=j;
                }
            }
            if(w ==-1) break; 
            else{
                N[w] = 0; // add w to N
            }
            
            int Dv=-1;
            // update D(v) for all v adjacent to w and not in N
            for(int v=0;v<5;v++){
                if(w==v) continue;
                for (int k=0;k<20;k++) {
                    if(Dv!=-1) break;
                    if(topology_linkcost[w][k]!=null) {
                        int linkW=topology_linkcost[w][k].link;
                        for (int j=0; j<20;j++) {
                            if(Dv!=-1) break;
                            if(topology_linkcost[v][j]!= null && linkW == topology_linkcost[v][j].link){
                                Dv=topology_linkcost[w][k].cost;
                                break;
                            }
                        }
                    }
                }
                if(Dv >0 && N[v]==-1){
                    int newDv = Dv+ribCost[router_id-1][w];
                    if(ribCost[router_id-1][v] > newDv){
                        ribCost[router_id-1][v] = newDv;
                        ribNext[router_id-1][v] = ribNext[router_id-1][w]; 
                    } 
                }
                Dv =-1;
            }
        }
    }

    private static void printTopoandRib(PrintWriter routerWriter){
        // print topology
        routerWriter.println("# Topology database");
        for (int i=0; i<5; i++) {
            int to = i+1;
            int nbr = topology_nbr[i];
            routerWriter.println("R"+router_id+" -> R"+to+" nbr link "+nbr);
            for(int j=0;j<20;j++) {
                if(topology_linkcost[i][j] != null) {
                    int link =topology_linkcost[i][j].link;
                    int cost=topology_linkcost[i][j].cost;
                    routerWriter.println("R"+router_id+" -> R"+to+" link "+link+" cost "+cost);
                }
            }
        }
        // print RIB
        routerWriter.println("# RIB");
        for (int i=0; i<5;i++) {
            int to =i+1;
            if(ribNext[router_id-1][i]==-2){
                routerWriter.println("R"+router_id+" -> R"+to+" -> Local, 0");
            }
            else if(ribNext[router_id-1][i] == -1){
                routerWriter.println("R"+router_id+" -> R"+to+" -> INF, INF");
            }
            else{
                int next=ribNext[router_id-1][i];
                int cost=ribCost[router_id-1][i];
                routerWriter.println("R"+router_id+" -> R"+to+" -> R"+next+", "+cost);
            }
        }
        routerWriter.print("\n");
        routerWriter.flush();
    }
}


class pkt_HELLO { 
	public int router_id; 
	public int link_id; 

	pkt_HELLO(int router_id, int link_id) {
		this.router_id = router_id;
		this.link_id = link_id;
	}

	public byte[] getUDPdata() {
		ByteBuffer buffer = ByteBuffer.allocate(8);
		buffer.order(ByteOrder.LITTLE_ENDIAN);
		buffer.putInt(router_id);
		buffer.putInt(link_id);
		return buffer.array();
	}
	public static pkt_HELLO parseUDPdata(byte[] UDPdata) throws Exception {
		ByteBuffer buffer = ByteBuffer.wrap(UDPdata);
		buffer.order(ByteOrder.LITTLE_ENDIAN);
		int router_id = buffer.getInt();
		int link_id = buffer.getInt();
		return new pkt_HELLO(router_id, link_id);
	}
} 

class pkt_LSPDU {
	public int sender;
    public int router_id;
    public int link_id;
    public int cost;
    public int via;

	pkt_LSPDU( int sender,int router_id, int link_id, int cost, int via) {
		this.sender = sender;
		this.router_id = router_id;
		this.link_id = link_id;
		this.cost = cost;
		this.via = via;
	}

	public byte[] getUDPdata() {
		ByteBuffer buffer = ByteBuffer.allocate(20);
		buffer.order(ByteOrder.LITTLE_ENDIAN);
		buffer.putInt(sender);
		buffer.putInt(router_id);
		buffer.putInt(link_id);
		buffer.putInt(cost);
		buffer.putInt(via);
		return buffer.array();
	}
	public static pkt_LSPDU parseUDPdata(byte[] UDPdata) throws Exception {
		ByteBuffer buffer = ByteBuffer.wrap(UDPdata);
		buffer.order(ByteOrder.LITTLE_ENDIAN);
		int sender = buffer.getInt();
		int router_id = buffer.getInt();
		int link_id = buffer.getInt();
		int cost = buffer.getInt();
		int via = buffer.getInt();
		return new pkt_LSPDU(sender,router_id, link_id, cost, via);
	}
}

class pkt_INIT { 
	public int router_id; 

	pkt_INIT(int router_id) {
		this.router_id = router_id;
	}

	public byte[] getUDPdata() {
		ByteBuffer buffer = ByteBuffer.allocate(4);
		buffer.order(ByteOrder.LITTLE_ENDIAN);
		buffer.putInt(router_id);
		return buffer.array();
	}
	
	public static pkt_INIT parseUDPdata(byte[] UDPdata) throws Exception {
		ByteBuffer buffer = ByteBuffer.wrap(UDPdata);
		buffer.order(ByteOrder.LITTLE_ENDIAN);
		int router_id = buffer.getInt();
		return new pkt_INIT(router_id);
	}
} 

class link_cost { 
	public int link; 
	public int cost; 

	public link_cost(int link, int cost) {
		this.link = link;
		this.cost = cost;
	}
} 

class circuit_DB { 
    public int nbr_link; 
    public link_cost linkcost[]; 

    public circuit_DB(int nbr_link, link_cost linkcost[]) {
        this.nbr_link = nbr_link;
        this.linkcost = linkcost;
    }
    public static circuit_DB parseUDPdata(byte[] UDPdata) throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(UDPdata);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        int length = buffer.getInt();
        link_cost[] newl_c = new link_cost[length];
        for (int i=0; i < length; i++) {
            int link_id = buffer.getInt();
            int cost = buffer.getInt();
            newl_c[i] = new link_cost(link_id, cost);
        }
        return new circuit_DB(length,newl_c);
    }
} 

