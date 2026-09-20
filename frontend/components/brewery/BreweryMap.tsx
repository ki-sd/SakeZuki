interface BreweryMapProps{
    name:string;
    address:string;
}

export default function BreweryMap({name,address}:BreweryMapProps){
    const apiKey=process.env.NEXT_PUBLIC_GOOGLE_MAPS_API_KEY;

    if(!apiKey || !address){
        return null;
    }

    const query=encodeURIComponent(`${name} ${address}`);
    const mapUrl=`https://www.google.com/maps/embed/v1/place?key=${apiKey}&q=${query}`;
    const googleMapsUrl=`https://www.google.com/maps/search/?api=1&query=${query}`;

    return (
        <div className={"mt-10"}>
            <p className={"mb-3 text-sm text-gray-500"}>
                위치
            </p>

            <div className={"overflow-hidden rounded-xl border border-gray-200"}>
                <iframe src={mapUrl} title={`${name} 위치`}
                        loading={"lazy"} referrerPolicy={"strict-origin-when-cross-origin"}
                        className={"h-[400px] w-full border-0"} allowFullScreen/>
            </div>

            <a href={googleMapsUrl} target="_blank" rel="noopener noreferrer"
               className={"mt-3 inline-block text-sm font-medium text-gray-700 underline underline-offset-4 hover:text-gray-900"}>
                Google 지도에서 보기
            </a>
        </div>
    );
}