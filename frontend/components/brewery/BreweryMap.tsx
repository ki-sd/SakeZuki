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
        <div className={"mt-8 border-t border-stone-200 pt-7"}>
            <p className={"mb-3 text-sm font-medium text-stone-700"}>
                위치
            </p>

            <div className={"overflow-hidden rounded-lg border border-stone-200 bg-stone-100"}>
                <iframe src={mapUrl} title={`${name} 위치`}
                        loading={"lazy"} referrerPolicy={"strict-origin-when-cross-origin"}
                        className={"h-64 w-full border-0 sm:h-80"} allowFullScreen/>
            </div>

            <a href={googleMapsUrl} target="_blank" rel="noopener noreferrer"
               className={"mt-3 inline-block rounded-sm text-sm font-medium text-stone-700 underline underline-offset-4 hover:text-stone-900 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-stone-700"}>
                Google 지도에서 보기
            </a>
        </div>
    );
}
